package io.github.estebansa19.chunkkeeper.game;

import io.github.estebansa19.chunkkeeper.web.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installed Minecraft versions (client jars of the usual launchers) and what Chunk Keeper reads from them. */
public final class GameVersions {
    private GameVersions() {}

    /** Old-chunk markers we measured, for when that version's game files are not installed. */
    private static final Map<String, Integer> KNOWN_MARKERS = Map.of("26.1.2", 4295, "26.3", 4997);

    public record Installed(String id, int dataVersion, Path clientJar) {}

    /** {@code dataVersion}: chunks saved before it get the old-chunk mark when this version loads them. */
    public record Marker(int dataVersion, String source) {}

    private static final Map<Path, Installed> jarInfo = new HashMap<>();

    static List<Path> clientJarRoots() {
        String home = System.getProperty("user.home");
        String appData = Optional.ofNullable(System.getenv("APPDATA")).orElse(home + "/AppData/Roaming");
        return List.of(
            Paths.get(appData, "PrismLauncher/libraries/com/mojang/minecraft"),
            Paths.get(home, ".local/share/PrismLauncher/libraries/com/mojang/minecraft"),
            Paths.get(home, "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft"),
            Paths.get(appData, ".minecraft/versions"), Paths.get(home, ".minecraft/versions"),
            Paths.get(home, "Library/Application Support/minecraft/versions"),
            Paths.get(appData, "ModrinthApp/meta/versions"));
    }

    /** Every client jar found, as (folder name, jar). */
    static List<Map.Entry<String, Path>> clientJars() {
        List<Map.Entry<String, Path>> out = new ArrayList<>();
        for (Path root : clientJarRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.list(root)) {
                for (Path v : (Iterable<Path>) s::iterator) {
                    String n = v.getFileName().toString();
                    for (Path c : List.of(v.resolve("minecraft-" + n + "-client.jar"), v.resolve(n + ".jar")))
                        if (Files.isRegularFile(c)) out.add(Map.entry(n, c));
                }
            } catch (IOException ignored) {
                // unreadable launcher folder
            }
        }
        return out;
    }

    /** The client jar of {@code version}, or else the most recently changed one (null when none is installed). */
    public static Path findClientJar(String version) {
        List<Path> all = new ArrayList<>();
        for (Map.Entry<String, Path> e : clientJars()) {
            if (e.getKey().equals(version)) return e.getValue();
            all.add(e.getValue());
        }
        all.sort(Comparator.comparing((Path p) -> {
            try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
        }).reversed());
        return all.isEmpty() ? null : all.get(0);
    }

    /** Installed versions, newest world format first (one entry per version id). */
    public static List<Installed> installed() {
        Map<String, Installed> byId = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : clientJars()) {
            Installed i = describe(e.getValue());
            if (i != null) byId.putIfAbsent(i.id(), i);
        }
        List<Installed> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingInt(Installed::dataVersion).reversed());
        return out;
    }

    public static Installed find(String id) {
        for (Installed i : installed()) if (i.id().equals(id)) return i;
        return null;
    }

    /** The marker of {@code version}: read from its installed game files, else from the measured table, else null. */
    public static Marker blendingMarker(String version) {
        Installed i = find(version);
        if (i != null) {
            try (ZipFile z = new ZipFile(i.clientJar().toFile())) {
                ZipEntry e = z.getEntry("net/minecraft/util/datafix/DataFixers.class");
                if (e != null) {
                    Integer v;
                    try (InputStream in = z.getInputStream(e)) { v = intConstant(in.readAllBytes(), "BLENDING_VERSION"); }
                    if (v != null) return new Marker(v, "game files");
                }
            } catch (IOException ignored) {
                // fall back to the table
            }
        }
        Integer known = KNOWN_MARKERS.get(version);
        return known == null ? null : new Marker(known, "built-in table");
    }

    /** Minecraft version of the launcher instance the world lives in (Prism/MultiMC/PolyMC, CurseForge), or null. */
    public static String instanceVersion(Path worldDir) {
        Path p = worldDir.toAbsolutePath().normalize().getParent(); // saves
        for (int up = 0; up < 3 && p != null; up++, p = p.getParent()) {
            try {
                Path mmc = p.resolve("mmc-pack.json");
                if (Files.isRegularFile(mmc)) {
                    Object comps = Json.object(Files.readString(mmc, StandardCharsets.UTF_8)).get("components");
                    if (comps instanceof List<?> l)
                        for (Object c : l)
                            if (c instanceof Map<?, ?> m && "net.minecraft".equals(m.get("uid")) && m.get("version") != null) return String.valueOf(m.get("version"));
                    return null;
                }
                Path cf = p.resolve("minecraftinstance.json");
                if (Files.isRegularFile(cf)) {
                    Object v = Json.object(Files.readString(cf, StandardCharsets.UTF_8)).get("gameVersion");
                    return v == null ? null : String.valueOf(v);
                }
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /** id and world_version from a client or server jar's version.json, or null. */
    public static synchronized Installed describe(Path jar) {
        Installed cached = jarInfo.get(jar);
        if (cached != null) return cached;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("version.json");
            if (e == null) return null;
            Map<String, Object> v;
            try (InputStream in = z.getInputStream(e)) { v = Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            if (!(v.get("id") instanceof String id) || !(v.get("world_version") instanceof Number dv)) return null;
            Installed i = new Installed(id, dv.intValue(), jar);
            jarInfo.put(jar, i);
            return i;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The value of a {@code static final int} field, read from a class file's ConstantValue attribute (null if absent). */
    public static Integer intConstant(byte[] classFile, String field) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
            if (in.readInt() != 0xCAFEBABE) return null;
            in.readUnsignedShort();
            in.readUnsignedShort();
            int n = in.readUnsignedShort();
            Object[] cp = new Object[n];
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> cp[i] = in.readUTF();
                    case 3 -> cp[i] = in.readInt();
                    case 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; } // long and double take two slots
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    default -> { return null; }
                }
            }
            in.skipBytes(6); // access flags, this class, super class
            in.skipBytes(2 * in.readUnsignedShort()); // interfaces
            int fields = in.readUnsignedShort();
            for (int f = 0; f < fields; f++) {
                in.readUnsignedShort();
                boolean match = field.equals(cp[in.readUnsignedShort()]);
                in.readUnsignedShort();
                int attrs = in.readUnsignedShort();
                for (int a = 0; a < attrs; a++) {
                    Object name = cp[in.readUnsignedShort()];
                    int len = in.readInt();
                    if (match && "ConstantValue".equals(name)) return cp[in.readUnsignedShort()] instanceof Integer v ? v : null;
                    in.skipBytes(len);
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
