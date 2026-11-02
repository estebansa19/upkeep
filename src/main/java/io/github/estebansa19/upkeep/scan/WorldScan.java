package io.github.estebansa19.upkeep.scan;

import io.github.estebansa19.upkeep.render.BlockColors;
import io.github.estebansa19.upkeep.world.Dimension;
import io.github.estebansa19.upkeep.world.World;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans a world region by region (in the background and on demand), caching tiles and facts on disk,
 * and derives the world-wide "keep" suggestions (players, spawn, End island, structures farms need).
 */
public final class WorldScan implements Closeable {
    public static final int[] LOD_SIZE = { 512, 128, 32 };
    private static final Set<String> FARM_STRUCTURES = Set.of("minecraft:fortress", "minecraft:monument", "minecraft:swamp_hut",
        "minecraft:pillager_outpost");

    public final World world;
    public final Path cacheDir;
    public final BlockColors colors;
    private final ExecutorService pool;
    private final Map<String, RegionData> memory = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RegionData>> inflight = new ConcurrentHashMap<>();
    private final Map<String, StructureIndex> structures = new ConcurrentHashMap<>();
    private final Map<String, int[]> progress = new ConcurrentHashMap<>();         // dim key -> {done, total}
    private final Map<String, Map<Long, Integer>> global = new ConcurrentHashMap<>();
    private final Map<String, List<String>> globalNotes = new ConcurrentHashMap<>();

    public WorldScan(World world, Path cacheDir, BlockColors colors) {
        this.world = world;
        this.cacheDir = cacheDir;
        this.colors = colors;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "scan-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
    public static int keyX(long k) { return (int) (k >> 32); }
    public static int keyZ(long k) { return (int) k; }

    /** Starts scanning every region of every dimension (nearest to 0,0 first). */
    public void scanAll() {
        for (Dimension d : world.dimensions) {
            List<int[]> regions;
            try { regions = d.regions(); } catch (IOException e) { regions = List.of(); }
            regions.sort(Comparator.comparingInt(r -> r[0] * r[0] + r[1] * r[1]));
            int[] p = new int[] { 0, regions.size() };
            progress.put(d.key(), p);
            if (regions.isEmpty()) { finishDimension(d); continue; }
            AtomicInteger left = new AtomicInteger(regions.size());
            for (int[] r : regions)
                pool.submit(() -> {
                    try { region(d, r[0], r[1]); } catch (Exception ignored) { /* reported per region */ }
                    synchronized (p) { p[0]++; }
                    if (left.decrementAndGet() == 0) finishDimension(d);
                });
        }
    }

    public Map<String, int[]> progress() { return progress; }

    public boolean dimensionDone(Dimension d) { return global.containsKey(d.key()); }

    /** Facts for one region, from memory, the disk cache, or a fresh scan. */
    public RegionData region(Dimension d, int rx, int rz) throws IOException {
        String k = d.key() + "/" + rx + "/" + rz;
        long stamp = stamp(d, rx, rz);
        RegionData m = memory.get(k);
        if (m != null && m.stamp == stamp) return m;
        CompletableFuture<RegionData> mine = new CompletableFuture<>();
        CompletableFuture<RegionData> f = inflight.putIfAbsent(k, mine);
        if (f != null) {
            try { return f.get(); } catch (InterruptedException | ExecutionException e) { throw new IOException(e.getCause()); }
        }
        try {
            RegionData r = loadCached(d, rx, rz, stamp);
            if (r == null) r = scanAndCache(d, rx, rz, stamp);
            memory.put(k, r);
            mine.complete(r);
            return r;
        } catch (IOException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(k);
        }
    }

    /** PNG tile for a region at level of detail 0 (512 px), 1 (128 px) or 2 (32 px). */
    public Path tile(Dimension d, int rx, int rz, int lod) throws IOException {
        region(d, rx, rz);
        return tilePath(d, rx, rz, lod);
    }

    private Path dir(Dimension d) { return cacheDir.resolve(d.key()); }
    private Path tilePath(Dimension d, int rx, int rz, int lod) { return dir(d).resolve("r." + rx + "." + rz + "." + lod + ".png"); }

    private long stamp(Dimension d, int rx, int rz) throws IOException {
        long h = RegionData.FORMAT * 31L + colors.source.hashCode();
        for (String kind : Dimension.KINDS) {
            Path p = d.regionPath(kind, rx, rz);
            if (Files.exists(p)) h = h * 1_000_003L + Files.size(p) * 31 + Files.getLastModifiedTime(p).toMillis();
            else h = h * 1_000_003L + 7;
        }
        return h;
    }

    private RegionData loadCached(Dimension d, int rx, int rz, long stamp) {
        Path f = dir(d).resolve("r." + rx + "." + rz + ".dat");
        if (!Files.exists(f) || !Files.exists(tilePath(d, rx, rz, 2))) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            RegionData r = RegionData.read(in);
            return r != null && r.stamp == stamp ? r : null;
        } catch (IOException e) {
            return null;
        }
    }

    private RegionData scanAndCache(Dimension d, int rx, int rz, long stamp) throws IOException {
        StructureIndex si = structures.computeIfAbsent(d.key(), x -> new StructureIndex(d));
        RegionScanner.Result res = RegionScanner.scan(d, rx, rz, colors, si);
        RegionData r = res.data();
        r.stamp = stamp;
        Files.createDirectories(dir(d));
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 512, 512, res.argb(), 0, 512);
        for (int lod = 0; lod < LOD_SIZE.length; lod++) {
            BufferedImage out = lod == 0 ? img : scale(img, LOD_SIZE[lod]);
            Path tmp = tilePath(d, rx, rz, lod).resolveSibling("tmp-" + Thread.currentThread().threadId() + "-" + lod + ".png");
            ImageIO.write(out, "png", tmp.toFile());
            Files.move(tmp, tilePath(d, rx, rz, lod), StandardCopyOption.REPLACE_EXISTING);
        }
        Path f = dir(d).resolve("r." + rx + "." + rz + ".dat"), tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) { r.write(out); }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        return r;
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ world-wide suggestions

    /** Extra reasons per chunk (key -> bits) that need the whole dimension: players, spawn, End island, farm structures. */
    public Map<Long, Integer> globalReasons(Dimension d) { return global.getOrDefault(d.key(), Map.of()); }

    public List<String> globalNotes(Dimension d) { return globalNotes.getOrDefault(d.key(), List.of()); }

    private void finishDimension(Dimension d) {
        Map<Long, Integer> g = new HashMap<>();
        List<String> notes = new ArrayList<>();
        List<RegionData> all = new ArrayList<>();
        List<int[]> regions;
        try { regions = d.regions(); } catch (IOException e) { regions = List.of(); notes.add("could not list the regions: " + e.getMessage()); }
        for (int[] r : regions) {
            try {
                all.add(region(d, r[0], r[1]));
            } catch (IOException | RuntimeException e) { // keep going: one damaged file must not hide the rest
                notes.add("could not read region " + r[0] + "," + r[1] + ": " + e.getMessage());
            }
        }
        Set<Long> existing = new HashSet<>();
        for (RegionData r : all) for (int i = 0; i < 1024; i++) if (r.state[i] != RegionData.NONE) existing.add(key(r.cx(i), r.cz(i)));

        for (Object[] p : world.playerPlaces()) {
            if (!d.id.equals(p[0])) continue;
            int x = (Integer) p[1], z = (Integer) p[3];
            int rad = "world spawn".equals(p[4]) ? Math.max(1, world.spawnChunkRadius) : 1;
            addArea(g, existing, x >> 4, z >> 4, rad, RegionData.R_PLAYER);
            notes.add(p[4] + " at " + x + " " + p[2] + " " + z);
        }
        if (d.id.equals("minecraft:the_end")) {
            int n = 0;
            for (long k : existing) {
                int cx = keyX(k), cz = keyZ(k);
                if (cx * cx + cz * cz <= 13 * 13) { g.merge(k, RegionData.R_END, (a, b) -> a | b); n++; }
            }
            if (n > 0) notes.add("main End island (exit portal, pillars, gateways): " + n + " chunks");
            for (RegionData r : all)
                for (int[] e : r.gatewayExits) { addArea(g, existing, e[0] >> 4, e[2] >> 4, 1, RegionData.R_END); notes.add("End gateway exit at " + e[0] + " " + e[1] + " " + e[2]); }
        }
        for (RegionData r : all)
            for (Map.Entry<Integer, List<String[]>> e : r.references.entrySet()) {
                int i = e.getKey();
                if ((r.reasons[i] & (RegionData.R_BUILD | RegionData.R_STORAGE | RegionData.R_MOBS)) == 0) continue;
                for (String[] ref : e.getValue()) {
                    if (!FARM_STRUCTURES.contains(ref[0])) continue;
                    long sk = key(Integer.parseInt(ref[1]), Integer.parseInt(ref[2]));
                    if (existing.contains(sk) && (g.getOrDefault(sk, 0) & RegionData.R_STRUCTURE) == 0) {
                        g.merge(sk, RegionData.R_STRUCTURE, (a, b) -> a | b);
                        notes.add(ref[0].replace("minecraft:", "") + " start chunk " + ref[1] + "," + ref[2] + " (your build at chunk " + r.cx(i) + "," + r.cz(i) + " is inside it)");
                    }
                }
            }
        globalNotes.put(d.key(), notes);
        global.put(d.key(), g);
    }

    private static void addArea(Map<Long, Integer> g, Set<Long> existing, int cx, int cz, int rad, int bit) {
        for (int x = cx - rad; x <= cx + rad; x++)
            for (int z = cz - rad; z <= cz + rad; z++) {
                long k = key(x, z);
                if (existing.contains(k)) g.merge(k, bit, (a, b) -> a | b);
            }
    }

    /** Every chunk that exists in the dimension (any status). */
    public Set<Long> existingChunks(Dimension d) throws IOException {
        Set<Long> s = new HashSet<>();
        for (int[] r : d.regions()) {
            RegionData rd = region(d, r[0], r[1]);
            for (int i = 0; i < 1024; i++) if (rd.state[i] != RegionData.NONE) s.add(key(rd.cx(i), rd.cz(i)));
        }
        return s;
    }

    @Override
    public void close() { pool.shutdownNow(); }
}
