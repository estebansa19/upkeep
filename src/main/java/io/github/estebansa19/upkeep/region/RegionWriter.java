package io.github.estebansa19.upkeep.region;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/** Writes a fresh, compact region file from chunks given as stored bytes. */
public final class RegionWriter {
    private RegionWriter() {}

    /** One chunk to write: index 0..1023 in the region, stored form, original timestamp. */
    public record Entry(int index, RegionFile.RawChunk chunk, int timestamp) {}

    /**
     * Writes {@code entries} to {@code out}. Chunks too large for 255 sectors (or already external)
     * go to c.X.Z.mcc next to the region file, as Minecraft does.
     */
    public static void write(Path out, int rx, int rz, List<Entry> entries) throws IOException {
        int[] loc = new int[1024], ts = new int[1024];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int sector = 2;
        for (Entry e : entries) {
            RegionFile.RawChunk c = e.chunk();
            int cx = rx * 32 + (e.index() & 31), cz = rz * 32 + (e.index() >> 5);
            boolean external = c.external() || c.data().length + 5 > 255 * RegionFile.SECTOR;
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(one);
            if (external) {
                Files.write(out.resolveSibling("c." + cx + "." + cz + ".mcc"), c.data());
                d.writeInt(1);
                d.writeByte(c.compression() | RegionFile.EXTERNAL_FLAG);
            } else {
                Files.deleteIfExists(out.resolveSibling("c." + cx + "." + cz + ".mcc")); // the chunk was external before (re-written smaller)
                d.writeInt(c.data().length + 1);
                d.writeByte(c.compression());
                d.write(c.data());
            }
            int size = one.size();
            int sectors = (size + RegionFile.SECTOR - 1) / RegionFile.SECTOR;
            body.write(one.toByteArray());
            body.write(new byte[sectors * RegionFile.SECTOR - size]);
            loc[e.index()] = sector << 8 | sectors;
            ts[e.index()] = e.timestamp();
            sector += sectors;
        }
        try (DataOutputStream f = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            for (int v : loc) f.writeInt(v);
            for (int v : ts) f.writeInt(v);
            body.writeTo(f);
        }
    }
}
