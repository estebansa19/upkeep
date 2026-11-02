package io.github.estebansa19.upkeep.nbt;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT codec. Tags map to plain Java values:
 * Byte, Short, Integer, Long, Float, Double, byte[], String, {@link NbtList}, compound = LinkedHashMap, int[], long[].
 */
public final class Nbt {
    private Nbt() {}

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
        STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Reads an uncompressed or gzip-compressed root compound. */
    public static Map<String, Object> read(byte[] raw) throws IOException {
        InputStream is = new ByteArrayInputStream(raw);
        if (raw.length > 1 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) is = new GZIPInputStream(is);
        return readRoot(new DataInputStream(new BufferedInputStream(is)));
    }

    public static Map<String, Object> readFile(java.nio.file.Path p) throws IOException {
        return read(java.nio.file.Files.readAllBytes(p));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readRoot(DataInput in) throws IOException {
        byte t = in.readByte();
        if (t != COMPOUND) throw new IOException("root tag is not a compound (type " + t + ")");
        in.readUTF();
        return (Map<String, Object>) readPayload(in, COMPOUND, 0);
    }

    private static Object readPayload(DataInput in, byte t, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deeply");
        switch (t) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: { byte[] b = new byte[checkLen(in.readInt())]; in.readFully(b); return b; }
            case STRING: return in.readUTF();
            case LIST: {
                byte et = in.readByte();
                int n = checkLen(in.readInt());
                NbtList l = new NbtList(et);
                for (int i = 0; i < n; i++) l.add(readPayload(in, et, depth + 1));
                return l;
            }
            case COMPOUND: {
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    byte ct = in.readByte();
                    if (ct == END) return m;
                    String name = in.readUTF();
                    m.put(name, readPayload(in, ct, depth + 1));
                }
            }
            case INT_ARRAY: { int n = checkLen(in.readInt()); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case LONG_ARRAY: { int n = checkLen(in.readInt()); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad tag type " + t);
        }
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("bad NBT length " + n);
        return n;
    }

    public static byte typeOf(Object o) {
        if (o instanceof Byte) return BYTE;
        if (o instanceof Short) return SHORT;
        if (o instanceof Integer) return INT;
        if (o instanceof Long) return LONG;
        if (o instanceof Float) return FLOAT;
        if (o instanceof Double) return DOUBLE;
        if (o instanceof byte[]) return BYTE_ARRAY;
        if (o instanceof String) return STRING;
        if (o instanceof NbtList) return LIST;
        if (o instanceof Map) return COMPOUND;
        if (o instanceof int[]) return INT_ARRAY;
        if (o instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("not an NBT value: " + (o == null ? "null" : o.getClass()));
    }

    /** Uncompressed root compound with an empty name. */
    public static byte[] write(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writePayload(out, root);
        out.flush();
        return bos.toByteArray();
    }

    public static void writeFileGzip(java.nio.file.Path p, Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(write(root)); }
        java.nio.file.Files.write(p, bos.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutput out, Object o) throws IOException {
        switch (typeOf(o)) {
            case BYTE: out.writeByte((Byte) o); break;
            case SHORT: out.writeShort((Short) o); break;
            case INT: out.writeInt((Integer) o); break;
            case LONG: out.writeLong((Long) o); break;
            case FLOAT: out.writeFloat((Float) o); break;
            case DOUBLE: out.writeDouble((Double) o); break;
            case BYTE_ARRAY: { byte[] b = (byte[]) o; out.writeInt(b.length); out.write(b); break; }
            case STRING: out.writeUTF((String) o); break;
            case LIST: {
                NbtList l = (NbtList) o;
                out.writeByte(l.isEmpty() ? l.elementType() : typeOf(l.get(0)));
                out.writeInt(l.size());
                for (Object e : l) writePayload(out, e);
                break;
            }
            case COMPOUND: {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(END);
                break;
            }
            case INT_ARRAY: { int[] a = (int[]) o; out.writeInt(a.length); for (int v : a) out.writeInt(v); break; }
            case LONG_ARRAY: { long[] a = (long[]) o; out.writeInt(a.length); for (long v : a) out.writeLong(v); break; }
            default: throw new IllegalStateException();
        }
    }

    // ------------------------------------------------------------ typed getters (null-safe)

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object parent, String key) {
        Object o = parent instanceof Map<?, ?> m ? m.get(key) : null;
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    public static NbtList list(Object parent, String key) {
        Object o = parent instanceof Map<?, ?> m ? m.get(key) : null;
        return o instanceof NbtList l ? l : null;
    }

    public static String string(Object parent, String key) {
        Object o = parent instanceof Map<?, ?> m ? m.get(key) : null;
        return o instanceof String s ? s : null;
    }

    public static long number(Object parent, String key, long def) {
        Object o = parent instanceof Map<?, ?> m ? m.get(key) : null;
        return o instanceof Number n ? n.longValue() : def;
    }

    public static double decimal(Object parent, String key, double def) {
        Object o = parent instanceof Map<?, ?> m ? m.get(key) : null;
        return o instanceof Number n ? n.doubleValue() : def;
    }

    public static boolean has(Object parent, String key) {
        return parent instanceof Map<?, ?> m && m.containsKey(key);
    }

    /** Block position stored as int[3], a list of 3 numbers, or a compound {X,Y,Z}. */
    public static int[] pos(Object o) {
        if (o instanceof int[] a && a.length >= 3) return new int[] { a[0], a[1], a[2] };
        if (o instanceof NbtList l && l.size() >= 3 && l.get(0) instanceof Number) {
            return new int[] { (int) Math.floor(((Number) l.get(0)).doubleValue()), (int) Math.floor(((Number) l.get(1)).doubleValue()),
                (int) Math.floor(((Number) l.get(2)).doubleValue()) };
        }
        if (o instanceof Map<?, ?> m && m.get("X") instanceof Number x && m.get("Y") instanceof Number y && m.get("Z") instanceof Number z)
            return new int[] { x.intValue(), y.intValue(), z.intValue() };
        return null;
    }
}
