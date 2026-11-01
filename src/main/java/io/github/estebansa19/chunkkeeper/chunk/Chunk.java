package io.github.estebansa19.chunkkeeper.chunk;

import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.nbt.NbtList;

import java.util.*;

/**
 * Read-only view of one chunk from a region file. Supports the 1.18+ layout (including the 26.x palette
 * variants: plain strings, {"": value} wrappers, {id, properties}) and the older "Level" layout (1.13-1.17).
 */
public final class Chunk {
    /** First DataVersion with the 1.18 chunk layout (21w43a). */
    public static final int DV_1_18 = 2844;
    /** First DataVersion where packed arrays no longer span longs (20w17a). */
    private static final int DV_NO_SPANNING = 2527;

    public final int cx, cz;
    public final Map<String, Object> root;   // the whole chunk compound
    public final Map<String, Object> body;   // root (1.18+) or root.Level (older)
    public final int dataVersion;
    public final boolean legacy;
    public final String status;              // namespace stripped, e.g. "full", "carvers"
    public final long inhabitedTime, lastUpdate;
    public final int minSection;
    private final Map<Integer, Section> sections = new HashMap<>();

    public Chunk(int cx, int cz, Map<String, Object> root) {
        this.cx = cx;
        this.cz = cz;
        this.root = root;
        this.dataVersion = (int) Nbt.number(root, "DataVersion", 0);
        Map<String, Object> level = Nbt.compound(root, "Level");
        this.legacy = level != null && !root.containsKey("sections");
        this.body = legacy ? level : root;
        String st = Nbt.string(body, "Status");
        this.status = st == null ? (legacy ? "full" : "empty") : st.replace("minecraft:", "");
        this.inhabitedTime = Nbt.number(body, "InhabitedTime", 0);
        this.lastUpdate = Nbt.number(body, "LastUpdate", 0);
        NbtList secs = Nbt.list(body, legacy ? "Sections" : "sections");
        int min = Integer.MAX_VALUE;
        if (secs != null)
            for (Object o : secs) {
                if (!(o instanceof Map<?, ?> s)) continue;
                Section sec = legacy ? Section.legacy(s, dataVersion >= DV_NO_SPANNING) : Section.modern(s);
                if (sec == null) continue;
                sections.put(sec.y, sec);
                min = Math.min(min, sec.y);
            }
        long yPos = Nbt.number(body, "yPos", Long.MIN_VALUE);
        this.minSection = yPos != Long.MIN_VALUE ? (int) yPos : min == Integer.MAX_VALUE ? 0 : Math.min(min, legacy ? 0 : min);
    }

    public boolean isFull() {
        return status.equals("full") || status.equals("postprocessed") || status.equals("fullchunk");
    }

    public int minY() { return minSection * 16; }

    public Collection<Section> sections() { return sections.values(); }

    public Section section(int sy) { return sections.get(sy); }

    /** Block id ("minecraft:stone") at chunk-local x/z and absolute y; air outside stored sections. */
    public String block(int lx, int y, int lz) {
        Section s = sections.get(Math.floorDiv(y, 16));
        return s == null ? "minecraft:air" : s.blockId(Section.index(lx, Math.floorMod(y, 16), lz));
    }

    public Map<String, Object> blockProps(int lx, int y, int lz) {
        Section s = sections.get(Math.floorDiv(y, 16));
        return s == null ? null : s.blockProps(Section.index(lx, Math.floorMod(y, 16), lz));
    }

    /** Biome id at chunk-local x/z and absolute y (nearest stored section), or null if unknown. */
    public String biome(int lx, int y, int lz) {
        Section s = sections.get(Math.floorDiv(y, 16));
        if (s == null || s.biomes == null) {
            Section best = null;
            for (Section c : sections.values())
                if (c.biomes != null && (best == null || Math.abs(c.y * 16 - y) < Math.abs(best.y * 16 - y))) best = c;
            if (best == null) return null;
            s = best;
            y = Math.max(s.y * 16, Math.min(s.y * 16 + 15, y));
        }
        return s.biome(lx, Math.floorMod(y, 16), lz);
    }

    /**
     * Heightmap as 256 absolute y values of the highest block (+1 convention removed: value = top block y),
     * index lz*16+lx, or null when missing.
     */
    public int[] heightmap(String type) {
        Map<String, Object> hm = Nbt.compound(body, "Heightmaps");
        Object o = hm == null ? null : hm.get(type);
        if (!(o instanceof long[] a) || a.length == 0) return null;
        int[] out = new int[256];
        boolean spanning = dataVersion < DV_NO_SPANNING;
        int bits = spanning ? (a.length * 64) / 256 : 64 / ((256 + a.length - 1) / a.length);
        for (int i = 0; i < 256; i++) out[i] = (int) unpack(a, i, bits, spanning) - 1 + minY();
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> blockEntities() {
        NbtList l = Nbt.list(body, legacy ? "TileEntities" : "block_entities");
        List<Map<String, Object>> r = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof Map<?, ?>) r.add((Map<String, Object>) o);
        return r;
    }

    /** Entities stored inside the chunk itself (before 1.17); newer versions use the entities folder. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> legacyEntities() {
        NbtList l = legacy ? Nbt.list(body, "Entities") : null;
        List<Map<String, Object>> r = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof Map<?, ?>) r.add((Map<String, Object>) o);
        return r;
    }

    public boolean hasBlendingData() { return body.containsKey("blending_data"); }

    /** Structure starts stored in this chunk: id -> piece bounding boxes {x1,y1,z1,x2,y2,z2}. */
    public Map<String, List<int[]>> structureStarts() {
        Map<String, List<int[]>> out = new LinkedHashMap<>();
        Map<String, Object> st = Nbt.compound(body, legacy ? "Structures" : "structures");
        Map<String, Object> starts = st == null ? null : Nbt.compound(st, legacy ? "Starts" : "starts");
        if (starts == null) return out;
        for (Map.Entry<String, Object> e : starts.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> s)) continue;
            String id = Nbt.string(s, "id");
            if (id == null || id.equals("INVALID")) continue;
            NbtList children = Nbt.list(s, "Children");
            if (children == null) children = Nbt.list(s, "children");
            List<int[]> boxes = new ArrayList<>();
            if (children != null)
                for (Object c : children) if (c instanceof Map<?, ?> cm && cm.get("BB") instanceof int[] bb && bb.length == 6) boxes.add(bb.clone());
            out.put(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), boxes);
        }
        return out;
    }

    /** Structures with pieces in this chunk: id -> start chunk positions as {cx, cz}. */
    public Map<String, List<int[]>> structureReferences() {
        Map<String, List<int[]>> out = new LinkedHashMap<>();
        Map<String, Object> st = Nbt.compound(body, legacy ? "Structures" : "structures");
        Map<String, Object> refs = st == null ? null : Nbt.compound(st, "References");
        if (refs == null) return out;
        for (Map.Entry<String, Object> e : refs.entrySet()) {
            if (!(e.getValue() instanceof long[] a) || a.length == 0) continue;
            List<int[]> l = new ArrayList<>();
            for (long v : a) l.add(new int[] { (int) v, (int) (v >> 32) });
            out.put(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), l);
        }
        return out;
    }

    static long unpack(long[] a, int i, int bits, boolean spanning) {
        long mask = (1L << bits) - 1;
        if (!spanning) {
            int per = 64 / bits;
            int li = i / per;
            if (li >= a.length) return 0;
            return (a[li] >>> ((i % per) * bits)) & mask;
        }
        long bit = (long) i * bits;
        int li = (int) (bit >>> 6), off = (int) (bit & 63);
        if (li >= a.length) return 0;
        long v = a[li] >>> off;
        if (off + bits > 64 && li + 1 < a.length) v |= a[li + 1] << (64 - off);
        return v & mask;
    }

    static String normalize(String id) {
        int b = id.indexOf('[');
        if (b >= 0) id = id.substring(0, b);
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** One 16x16x16 section: block palette + indices, optional biome palette + indices. */
    public static final class Section {
        public final int y;
        final String[] ids;
        final Map<String, Object>[] props;
        final long[] data;
        final int bits;
        final boolean spanning;
        String[] biomes;
        long[] biomeData;

        @SuppressWarnings("unchecked")
        private Section(int y, int n, long[] data, int bits, boolean spanning) {
            this.y = y;
            this.ids = new String[n];
            this.props = new Map[n];
            this.data = data;
            this.bits = bits;
            this.spanning = spanning;
        }

        static int index(int lx, int ly, int lz) { return (ly << 8) | (lz << 4) | lx; }

        static Section modern(Map<?, ?> s) {
            Object yo = s.get("Y");
            if (!(yo instanceof Number yn)) return null;
            Map<String, Object> bs = Nbt.compound(s, "block_states");
            NbtList pal = bs == null ? null : Nbt.list(bs, "palette");
            Section sec;
            if (pal == null || pal.isEmpty()) {
                sec = new Section(yn.intValue(), 1, null, 0, false);
                sec.ids[0] = "minecraft:air";
            } else {
                long[] d = bs.get("data") instanceof long[] a ? a : null;
                sec = new Section(yn.intValue(), pal.size(), d, Math.max(4, ceilLog2(pal.size())), false);
                for (int i = 0; i < pal.size(); i++) sec.setEntry(i, pal.get(i));
            }
            Map<String, Object> bm = Nbt.compound(s, "biomes");
            NbtList bp = bm == null ? null : Nbt.list(bm, "palette");
            if (bp != null && !bp.isEmpty()) {
                sec.biomes = new String[bp.size()];
                for (int i = 0; i < bp.size(); i++) {
                    Object e = bp.get(i);
                    if (e instanceof Map<?, ?> em && em.containsKey("")) e = em.get("");
                    sec.biomes[i] = e instanceof String str ? normalize(str) : String.valueOf(e);
                }
                sec.biomeData = bm.get("data") instanceof long[] a ? a : null;
            }
            return sec;
        }

        static Section legacy(Map<?, ?> s, boolean noSpanning) {
            Object yo = s.get("Y");
            NbtList pal = Nbt.list(s, "Palette");
            if (!(yo instanceof Number yn) || pal == null || pal.isEmpty()) return null;
            long[] d = s.get("BlockStates") instanceof long[] a ? a : null;
            Section sec = new Section(yn.intValue(), pal.size(), d, Math.max(4, ceilLog2(pal.size())), !noSpanning);
            for (int i = 0; i < pal.size(); i++) sec.setEntry(i, pal.get(i));
            return sec;
        }

        @SuppressWarnings("unchecked")
        private void setEntry(int i, Object e) {
            if (e instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("")) e = m.get("");
            if (e instanceof String str) {
                ids[i] = normalize(str);
            } else if (e instanceof Map<?, ?> m) {
                Object id = m.containsKey("id") ? m.get("id") : m.get("Name");
                Object pr = m.containsKey("properties") ? m.get("properties") : m.get("Properties");
                ids[i] = id instanceof String str ? normalize(str) : "minecraft:air";
                props[i] = pr instanceof Map<?, ?> ? (Map<String, Object>) pr : null;
            } else {
                ids[i] = "minecraft:air";
            }
        }

        public int paletteSize() { return ids.length; }
        public String paletteId(int p) { return ids[p]; }
        public Map<String, Object> paletteProps(int p) { return props[p]; }

        /** Palette index of block i (0..4095). */
        public int paletteIndex(int i) {
            if (ids.length == 1 || data == null) return 0;
            int v = (int) unpack(data, i, bits, spanning);
            return v < ids.length ? v : 0;
        }

        public String blockId(int i) { return ids[paletteIndex(i)]; }
        public Map<String, Object> blockProps(int i) { return props[paletteIndex(i)]; }

        String biome(int lx, int ly, int lz) {
            if (biomes == null) return null;
            if (biomes.length == 1 || biomeData == null) return biomes[0];
            int b = ceilLog2(biomes.length);
            int i = ((ly >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
            int v = (int) unpack(biomeData, i, b, false);
            return v < biomes.length ? biomes[v] : biomes[0];
        }

        static int ceilLog2(int n) { return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1); }
    }
}
