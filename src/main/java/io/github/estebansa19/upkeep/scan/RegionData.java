package io.github.estebansa19.upkeep.scan;

import java.io.*;
import java.util.*;

/** Per-chunk facts for one region (32x32 chunks), cached on disk next to the rendered tile. */
public final class RegionData {
    static final int FORMAT = 5; // bump whenever scan results change, so caches are rebuilt

    public static final byte NONE = 0, PROTO = 1, FULL = 2, BROKEN = 3;

    // reason bits (shared with the web UI)
    public static final int R_BUILD = 1, R_STORAGE = 2, R_MOBS = 4, R_PORTAL = 8, R_PLAYER = 16, R_END = 32, R_STRUCTURE = 64;

    public final int rx, rz;
    public final byte[] state = new byte[1024];
    public final int[] dataVersion = new int[1024];
    public final int[] inhabitedSec = new int[1024];
    public final int[] reasons = new int[1024];
    public final int[] playerBlocks = new int[1024];      // outside structure pieces
    public final int[] structureBlocks = new int[1024];   // player-looking blocks inside structure pieces
    public final int[] playerBlockEntities = new int[1024];
    public final int[] containersWithItems = new int[1024];
    public final int[] playerEntities = new int[1024];
    public final int[] villagers = new int[1024];
    public final int[] portalBlocks = new int[1024];
    public final byte[] blending = new byte[1024];
    public final byte[] waterEdges = new byte[1024];      // bits: 1 north, 2 south, 4 west, 8 east
    public final Map<Integer, List<String>> starts = new TreeMap<>();      // chunk index -> structure ids starting here
    public final Map<Integer, List<String[]>> references = new TreeMap<>(); // chunk index -> {id, startCx, startCz}
    public final List<int[]> gatewayExits = new ArrayList<>();               // End gateway exits {x, y, z}
    public final List<String> errors = new ArrayList<>();
    public long stamp;                                                        // source file sizes/dates this was built from

    public RegionData(int rx, int rz) { this.rx = rx; this.rz = rz; }

    public static int index(int cx, int cz) { return (cx & 31) | ((cz & 31) << 5); }
    public int cx(int idx) { return rx * 32 + (idx & 31); }
    public int cz(int idx) { return rz * 32 + (idx >> 5); }

    void write(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT);
        out.writeInt(rx); out.writeInt(rz); out.writeLong(stamp);
        out.write(state); out.write(blending); out.write(waterEdges);
        for (int[] a : List.of(dataVersion, inhabitedSec, reasons, playerBlocks, structureBlocks, playerBlockEntities, containersWithItems,
            playerEntities, villagers, portalBlocks))
            for (int v : a) out.writeInt(v);
        out.writeInt(starts.size());
        for (Map.Entry<Integer, List<String>> e : starts.entrySet()) {
            out.writeShort(e.getKey()); out.writeShort(e.getValue().size());
            for (String s : e.getValue()) out.writeUTF(s);
        }
        out.writeInt(references.size());
        for (Map.Entry<Integer, List<String[]>> e : references.entrySet()) {
            out.writeShort(e.getKey()); out.writeShort(e.getValue().size());
            for (String[] s : e.getValue()) { out.writeUTF(s[0]); out.writeInt(Integer.parseInt(s[1])); out.writeInt(Integer.parseInt(s[2])); }
        }
        out.writeInt(gatewayExits.size());
        for (int[] g : gatewayExits) { out.writeInt(g[0]); out.writeInt(g[1]); out.writeInt(g[2]); }
        out.writeInt(errors.size());
        for (String s : errors) out.writeUTF(s.length() > 300 ? s.substring(0, 300) : s);
    }

    static RegionData read(DataInputStream in) throws IOException {
        if (in.readInt() != FORMAT) return null;
        RegionData d = new RegionData(in.readInt(), in.readInt());
        d.stamp = in.readLong();
        in.readFully(d.state); in.readFully(d.blending); in.readFully(d.waterEdges);
        for (int[] a : List.of(d.dataVersion, d.inhabitedSec, d.reasons, d.playerBlocks, d.structureBlocks, d.playerBlockEntities,
            d.containersWithItems, d.playerEntities, d.villagers, d.portalBlocks))
            for (int i = 0; i < 1024; i++) a[i] = in.readInt();
        for (int n = in.readInt(); n > 0; n--) {
            int idx = in.readShort();
            List<String> l = new ArrayList<>();
            for (int k = in.readShort(); k > 0; k--) l.add(in.readUTF());
            d.starts.put(idx, l);
        }
        for (int n = in.readInt(); n > 0; n--) {
            int idx = in.readShort();
            List<String[]> l = new ArrayList<>();
            for (int k = in.readShort(); k > 0; k--) l.add(new String[] { in.readUTF(), String.valueOf(in.readInt()), String.valueOf(in.readInt()) });
            d.references.put(idx, l);
        }
        for (int n = in.readInt(); n > 0; n--) d.gatewayExits.add(new int[] { in.readInt(), in.readInt(), in.readInt() });
        for (int n = in.readInt(); n > 0; n--) d.errors.add(in.readUTF());
        return d;
    }
}
