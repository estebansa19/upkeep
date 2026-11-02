package io.github.estebansa19.upkeep.region;

import io.github.estebansa19.upkeep.nbt.Nbt;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Read-only access to one Anvil region file (r.X.Z.mca): 32x32 chunks, 4 KiB sectors. */
public final class RegionFile implements Closeable {
    public static final int SECTOR = 4096;
    public static final int GZIP = 1, ZLIB = 2, NONE = 3, LZ4 = 4, CUSTOM = 127, EXTERNAL_FLAG = 128;

    private final Path path;
    private final int rx, rz;
    private final RandomAccessFile file;
    private final long fileLength;
    private final int[] locations = new int[1024];
    private final int[] timestamps = new int[1024];

    private RegionFile(Path path, int rx, int rz) throws IOException {
        this.path = path;
        this.rx = rx;
        this.rz = rz;
        if (Files.size(path) < 2 * SECTOR) { file = null; fileLength = 0; return; } // empty or truncated: no chunks
        file = new RandomAccessFile(path.toFile(), "r");
        fileLength = file.length();
        byte[] header = new byte[2 * SECTOR];
        file.readFully(header);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(header));
        for (int i = 0; i < 1024; i++) locations[i] = in.readInt();
        for (int i = 0; i < 1024; i++) timestamps[i] = in.readInt();
    }

    /** Opens r.X.Z.mca; the coordinates come from the file name. */
    public static RegionFile open(Path path) throws IOException {
        int[] c = coords(path.getFileName().toString());
        if (c == null) throw new IOException("not a region file name: " + path);
        return new RegionFile(path, c[0], c[1]);
    }

    /** {rx, rz} from "r.X.Z.mca", or null. */
    public static int[] coords(String name) {
        String[] p = name.split("\\.");
        if (p.length != 4 || !p[0].equals("r") || !p[3].equals("mca")) return null;
        try { return new int[] { Integer.parseInt(p[1]), Integer.parseInt(p[2]) }; } catch (NumberFormatException e) { return null; }
    }

    public Path path() { return path; }
    public int rx() { return rx; }
    public int rz() { return rz; }

    /** Index 0..1023 of a chunk inside its region. */
    public static int index(int cx, int cz) { return (cx & 31) | ((cz & 31) << 5); }

    public boolean has(int idx) { return file != null && (locations[idx] >>> 8) >= 2 && (locations[idx] & 0xff) > 0; }

    public int timestamp(int idx) { return timestamps[idx]; }

    public int chunkCount() { int n = 0; for (int i = 0; i < 1024; i++) if (has(i)) n++; return n; }

    /** The chunk exactly as stored (compressed), or null when absent. Throws on a corrupt entry. */
    public synchronized RawChunk raw(int idx) throws IOException {
        if (!has(idx)) return null;
        long off = (long) (locations[idx] >>> 8) * SECTOR;
        int sectors = locations[idx] & 0xff;
        if (off + 5 > fileLength) throw new IOException("chunk " + idx + " points past the end of " + path.getFileName());
        file.seek(off);
        int len = file.readInt();
        int comp = file.readUnsignedByte();
        int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >> 5);
        if ((comp & EXTERNAL_FLAG) != 0) {
            Path ext = path.resolveSibling("c." + cx + "." + cz + ".mcc");
            if (!Files.exists(ext)) throw new IOException("missing external chunk file " + ext.getFileName());
            return new RawChunk(comp & 0x7f, Files.readAllBytes(ext), true);
        }
        if (len < 1 || len > sectors * SECTOR - 4 || off + 4 + len > fileLength)
            throw new IOException("bad length " + len + " for chunk " + cx + "," + cz + " in " + path.getFileName());
        byte[] data = new byte[len - 1];
        file.readFully(data);
        return new RawChunk(comp, data, false);
    }

    public Map<String, Object> nbt(int idx) throws IOException {
        RawChunk r = raw(idx);
        return r == null ? null : Nbt.read(decompress(r));
    }

    public static byte[] decompress(RawChunk r) throws IOException {
        switch (r.compression()) {
            case GZIP: try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(r.data()))) { return in.readAllBytes(); }
            case ZLIB: try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(r.data()))) { return in.readAllBytes(); }
            case NONE: return r.data();
            case LZ4: return Lz4.decodeBlockStream(r.data());
            default: throw new IOException("unsupported chunk compression " + r.compression());
        }
    }

    @Override
    public void close() throws IOException { if (file != null) file.close(); }

    /** A chunk as stored: compression type (without the external flag) and the compressed bytes. */
    public record RawChunk(int compression, byte[] data, boolean external) {}
}
