package io.github.estebansa19.chunkkeeper.region;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Decoder for the lz4-java "LZ4Block" stream format that Minecraft uses for region compression type 4. */
final class Lz4 {
    private Lz4() {}

    private static final byte[] MAGIC = "LZ4Block".getBytes(StandardCharsets.US_ASCII);
    private static final int METHOD_RAW = 0x10, METHOD_LZ4 = 0x20;

    static byte[] decodeBlockStream(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = 0;
        while (p < in.length) {
            if (p + 21 > in.length) throw new IOException("truncated LZ4 block header");
            for (int i = 0; i < MAGIC.length; i++) if (in[p + i] != MAGIC[i]) throw new IOException("bad LZ4 block magic");
            int method = in[p + 8] & 0xf0;
            int compressed = le(in, p + 9), original = le(in, p + 13);
            p += 21; // magic 8 + token 1 + 3 ints (lengths, checksum)
            if (original == 0) break; // end mark
            if (compressed < 0 || original < 0 || p + compressed > in.length) throw new IOException("bad LZ4 block lengths");
            if (method == METHOD_RAW) out.write(in, p, compressed);
            else if (method == METHOD_LZ4) out.write(decodeBlock(in, p, compressed, original));
            else throw new IOException("unknown LZ4 block method " + method);
            p += compressed;
        }
        return out.toByteArray();
    }

    private static int le(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    static byte[] decodeBlock(byte[] src, int start, int len, int originalLen) throws IOException {
        byte[] dst = new byte[originalLen];
        int si = start, end = start + len, di = 0;
        try {
            while (si < end) {
                int token = src[si++] & 0xff;
                int lit = token >>> 4;
                if (lit == 15) { int b; do { b = src[si++] & 0xff; lit += b; } while (b == 255); }
                System.arraycopy(src, si, dst, di, lit);
                si += lit;
                di += lit;
                if (si >= end) break; // the last sequence carries literals only
                int offset = (src[si] & 0xff) | (src[si + 1] & 0xff) << 8;
                si += 2;
                if (offset == 0 || offset > di) throw new IOException("bad LZ4 match offset");
                int match = token & 0x0f;
                if (match == 15) { int b; do { b = src[si++] & 0xff; match += b; } while (b == 255); }
                match += 4;
                for (int k = 0; k < match; k++, di++) dst[di] = dst[di - offset]; // may overlap
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("corrupt LZ4 block", e);
        }
        if (di != originalLen) throw new IOException("LZ4 block size mismatch");
        return dst;
    }
}
