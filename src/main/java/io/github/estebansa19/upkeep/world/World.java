package io.github.estebansa19.upkeep.world;

import io.github.estebansa19.upkeep.nbt.Nbt;
import io.github.estebansa19.upkeep.nbt.NbtList;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** A Java Edition world folder (any layout from 1.13 on; 26.x keeps dimensions under dimensions/<ns>/<name>). */
public final class World {
    public final Path dir;
    public final String levelName;
    public final String versionName;
    public final int dataVersion;
    public final long lastPlayed;
    public final Long seed;
    public final List<Dimension> dimensions;
    public final int[] spawn;        // x, y, z (overworld unless spawnDimension says otherwise)
    public final String spawnDimension;
    public final int spawnChunkRadius;

    private World(Path dir, Map<String, Object> data, Long seed, List<Dimension> dims, int spawnChunkRadius) {
        this.dir = dir;
        this.levelName = Optional.ofNullable(Nbt.string(data, "LevelName")).orElse(dir.getFileName().toString());
        Map<String, Object> ver = Nbt.compound(data, "Version");
        this.versionName = ver == null ? "unknown" : Optional.ofNullable(Nbt.string(ver, "Name")).orElse("unknown");
        this.dataVersion = (int) Nbt.number(data, "DataVersion", 0);
        this.lastPlayed = Nbt.number(data, "LastPlayed", 0);
        this.seed = seed;
        this.dimensions = dims;
        Map<String, Object> sp = Nbt.compound(data, "spawn");
        int[] pos = sp == null ? null : Nbt.pos(sp.get("pos"));
        if (pos != null) {
            this.spawn = pos;
            this.spawnDimension = Optional.ofNullable(Nbt.string(sp, "dimension")).orElse("minecraft:overworld");
        } else {
            this.spawn = new int[] { (int) Nbt.number(data, "SpawnX", 0), (int) Nbt.number(data, "SpawnY", 64), (int) Nbt.number(data, "SpawnZ", 0) };
            this.spawnDimension = "minecraft:overworld";
        }
        this.spawnChunkRadius = spawnChunkRadius;
    }

    public static boolean isWorld(Path dir) { return Files.isRegularFile(dir.resolve("level.dat")); }

    public static World open(Path dir) throws IOException {
        dir = dir.toAbsolutePath().normalize();
        if (!isWorld(dir)) throw new IOException("no level.dat in " + dir);
        Map<String, Object> root = Nbt.readFile(dir.resolve("level.dat"));
        Map<String, Object> data = Optional.ofNullable(Nbt.compound(root, "Data")).orElse(root);

        Long seed = null;
        Map<String, Object> wgs = Nbt.compound(data, "WorldGenSettings");
        if (wgs != null && wgs.get("seed") instanceof Number n) seed = n.longValue();
        Path wgsFile = dir.resolve("data/minecraft/world_gen_settings.dat");
        if (seed == null && Files.isRegularFile(wgsFile)) {
            Map<String, Object> w = Nbt.readFile(wgsFile);
            Map<String, Object> d = Optional.ofNullable(Nbt.compound(w, "data")).orElse(w);
            if (d.get("seed") instanceof Number n) seed = n.longValue();
        }
        if (seed == null && data.get("RandomSeed") instanceof Number n) seed = n.longValue();

        return new World(dir, data, seed, findDimensions(dir), readSpawnChunkRadius(dir, data));
    }

    static List<Dimension> findDimensions(Path dir) throws IOException {
        Map<String, Dimension> found = new LinkedHashMap<>();
        Path dims = dir.resolve("dimensions");
        if (Files.isDirectory(dims)) {
            try (Stream<Path> nss = Files.list(dims)) {
                for (Path ns : (Iterable<Path>) nss::iterator) {
                    if (!Files.isDirectory(ns)) continue;
                    try (Stream<Path> names = Files.walk(ns, 3)) {
                        for (Path d : (Iterable<Path>) names::iterator) {
                            if (!Files.isDirectory(d.resolve("region")) || d.equals(ns)) continue;
                            String id = ns.getFileName() + ":" + ns.relativize(d).toString().replace('\\', '/');
                            found.putIfAbsent(id, new Dimension(id, d, dir.relativize(d).toString().replace('\\', '/')));
                        }
                    }
                }
            }
        }
        // pre-26 layout: overworld in the world root, Nether in DIM-1, End in DIM1
        if (!found.containsKey("minecraft:overworld") && Files.isDirectory(dir.resolve("region")))
            found.put("minecraft:overworld", new Dimension("minecraft:overworld", dir, ""));
        if (!found.containsKey("minecraft:the_nether") && Files.isDirectory(dir.resolve("DIM-1/region")))
            found.put("minecraft:the_nether", new Dimension("minecraft:the_nether", dir.resolve("DIM-1"), "DIM-1"));
        if (!found.containsKey("minecraft:the_end") && Files.isDirectory(dir.resolve("DIM1/region")))
            found.put("minecraft:the_end", new Dimension("minecraft:the_end", dir.resolve("DIM1"), "DIM1"));

        List<Dimension> out = new ArrayList<>();
        for (String id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) if (found.containsKey(id)) out.add(found.remove(id));
        out.addAll(found.values());
        return out;
    }

    private static int readSpawnChunkRadius(Path dir, Map<String, Object> data) {
        try {
            Path gr = dir.resolve("data/minecraft/game_rules.dat");
            if (Files.isRegularFile(gr)) {
                Map<String, Object> g = Nbt.readFile(gr);
                Map<String, Object> d = Optional.ofNullable(Nbt.compound(g, "data")).orElse(g);
                for (String k : List.of("minecraft:spawn_chunk_radius", "spawn_chunk_radius"))
                    if (d.get(k) != null) return Integer.parseInt(String.valueOf(d.get(k)).replaceAll("[^0-9-]", ""));
            }
            Map<String, Object> rules = Nbt.compound(data, "GameRules");
            if (rules != null && rules.get("spawnChunkRadius") instanceof String s) return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            // fall back to the vanilla default
        }
        return 2;
    }

    public Dimension dimension(String keyOrId) {
        for (Dimension d : dimensions) if (d.key().equals(keyOrId) || d.id.equals(keyOrId)) return d;
        return null;
    }

    /**
     * True while a running game (or server) holds session.lock. Opens it read-only with a shared lock, which fails while
     * the game holds its exclusive one; so the world is never opened for writing, and read-only copies work too.
     */
    public boolean isLocked() {
        Path lock = dir.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.READ)) {
            FileLock l = ch.tryLock(0, Long.MAX_VALUE, true);
            if (l == null) return true;
            l.release();
            return false;
        } catch (Exception e) {
            return true; // cannot open or lock it: assume the game has it
        }
    }

    /** Player files (players/data in 26.x, playerdata before). */
    public List<Path> playerFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String sub : List.of("players/data", "playerdata")) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) { s.filter(p -> p.toString().endsWith(".dat")).forEach(out::add); }
        }
        return out;
    }

    /** Places tied to players: {dimension id, x, y, z, label}. */
    public List<Object[]> playerPlaces() {
        List<Object[]> out = new ArrayList<>();
        List<Path> files;
        try { files = playerFiles(); } catch (IOException e) { files = List.of(); }
        for (Path p : files) {
            try { // one unreadable player file must not hide the others
                Map<String, Object> pl = Nbt.readFile(p);
                String name = p.getFileName().toString().replace(".dat", "");
                String dim = Optional.ofNullable(Nbt.string(pl, "Dimension")).orElse("minecraft:overworld");
                int[] pos = Nbt.pos(pl.get("Pos"));
                if (pos != null) out.add(new Object[] { dim, pos[0], pos[1], pos[2], "player " + name.substring(0, Math.min(8, name.length())) + " position" });
                Map<String, Object> rs = Nbt.compound(pl, "respawn");
                int[] rp = rs == null ? null : Nbt.pos(rs.get("pos"));
                if (rp != null) out.add(new Object[] { Optional.ofNullable(Nbt.string(rs, "dimension")).orElse("minecraft:overworld"), rp[0], rp[1], rp[2], "respawn point" });
                if (pl.containsKey("SpawnX"))
                    out.add(new Object[] { Optional.ofNullable(Nbt.string(pl, "SpawnDimension")).orElse("minecraft:overworld"),
                        (int) Nbt.number(pl, "SpawnX", 0), (int) Nbt.number(pl, "SpawnY", 0), (int) Nbt.number(pl, "SpawnZ", 0), "respawn point" });
                NbtList pearls = Nbt.list(pl, "ender_pearls");
                if (pearls != null)
                    for (Object o : pearls) {
                        int[] pp = Nbt.pos(o instanceof Map<?, ?> m ? m.get("Pos") : null);
                        String pd = o instanceof Map<?, ?> m ? Nbt.string(m, "ender_pearl_dimension") : null;
                        if (pp != null) out.add(new Object[] { pd == null ? dim : pd, pp[0], pp[1], pp[2], "thrown ender pearl" });
                    }
            } catch (IOException | RuntimeException ignored) {
                // unreadable player data: no places from it
            }
        }
        out.add(new Object[] { spawnDimension, spawn[0], spawn[1], spawn[2], "world spawn" });
        return out;
    }
}
