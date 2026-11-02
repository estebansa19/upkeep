package io.github.estebansa19.chunkkeeper.plan;

import io.github.estebansa19.chunkkeeper.game.ServerJars;
import io.github.estebansa19.chunkkeeper.game.ServerUpgrade;
import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.region.RegionFile;
import io.github.estebansa19.chunkkeeper.region.RegionWriter;
import io.github.estebansa19.chunkkeeper.scan.WorldScan;
import io.github.estebansa19.chunkkeeper.world.Dimension;
import io.github.estebansa19.chunkkeeper.world.World;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a new world folder: everything copied, except that in each selected dimension only the kept chunks
 * are written (region, entities and poi), so the game generates the rest again. The source is only read.
 */
public final class Applier {
    private Applier() {}

    /**
     * {@code stripBlending}: remove old-chunk marks from kept chunks. {@code upgrade}: first re-save the new copy with this
     * Minecraft server (so the version it is played in never marks the kept chunks again), then remove the marks. May be null.
     */
    public record Options(Path output, Map<String, Set<Long>> keep, boolean stripBlending, String newName, ServerJars.Ready upgrade) {
        public Options(Path output, Map<String, Set<Long>> keep, boolean stripBlending, String newName) { this(output, keep, stripBlending, newName, null); }
    }

    public static final class Progress {
        public volatile String phase = "starting";
        public final AtomicLong done = new AtomicLong(), total = new AtomicLong();
        public volatile String error;
        public volatile Result result;
    }

    public record DimResult(String key, int keptChunks, int removedChunks, int strippedBlending, int regionFilesWritten, int regionFilesDropped) {}

    /** {@code upgradedWith}: the server version that re-saved the copy, or null. */
    public record Result(Path output, List<DimResult> dims, long filesCopied, boolean verified, List<String> problems, String upgradedWith) {}

    public static Result run(World w, Options o, Progress p) throws IOException {
        Path src = w.dir, out = o.output().toAbsolutePath().normalize();
        if (w.isLocked()) throw new IOException("The world is open in Minecraft (session.lock is held). Close the game first.");
        if (out.startsWith(src) || src.startsWith(out)) throw new IOException("The output folder must be outside the world folder.");
        if (Files.exists(out)) {
            try (var s = Files.list(out)) { if (s.findAny().isPresent()) throw new IOException("Output folder is not empty: " + out); }
        }
        Set<Path> skip = new HashSet<>();
        List<Dimension> dims = new ArrayList<>();
        for (Dimension d : w.dimensions)
            if (o.keep().containsKey(d.key())) {
                dims.add(d);
                for (String kind : Dimension.KINDS) skip.add(d.folder(kind).toAbsolutePath().normalize());
            }

        p.phase = "copying world files";
        long[] copied = { 0 };
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (skip.contains(dir.toAbsolutePath().normalize())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(out.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(f, out.resolve(src.relativize(f).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                copied[0]++;
                return FileVisitResult.CONTINUE;
            }
        });

        for (Dimension d : dims) for (String kind : Dimension.KINDS) if (Files.isDirectory(d.folder(kind)))
            try (var s = Files.list(d.folder(kind))) { p.total.addAndGet(s.filter(x -> x.toString().endsWith(".mca")).count()); }

        List<DimResult> results = new ArrayList<>();
        for (Dimension d : dims) {
            p.phase = "writing " + d.label();
            Set<Long> keep = o.keep().get(d.key());
            int keptChunks = 0, removed = 0, stripped = 0, written = 0, dropped = 0;
            for (String kind : Dimension.KINDS) {
                Path inDir = d.folder(kind), outDir = out.resolve(src.relativize(inDir).toString());
                if (!Files.isDirectory(inDir)) continue;
                Files.createDirectories(outDir);
                List<Path> files;
                try (var s = Files.list(inDir)) { files = s.filter(x -> RegionFile.coords(x.getFileName().toString()) != null).sorted().toList(); }
                for (Path f : files) {
                    try (RegionFile rf = RegionFile.open(f)) {
                        List<RegionWriter.Entry> entries = new ArrayList<>();
                        for (int i = 0; i < 1024; i++) {
                            if (!rf.has(i)) continue;
                            int cx = rf.rx() * 32 + (i & 31), cz = rf.rz() * 32 + (i >> 5);
                            if (!keep.contains(WorldScan.key(cx, cz))) { if (kind.equals("region")) removed++; continue; }
                            RegionFile.RawChunk raw = rf.raw(i);
                            if (o.stripBlending() && o.upgrade() == null && kind.equals("region")) {
                                RegionFile.RawChunk s = stripBlending(raw);
                                if (s != raw) { stripped++; raw = s; }
                            }
                            entries.add(new RegionWriter.Entry(i, raw, rf.timestamp(i)));
                            if (kind.equals("region")) keptChunks++;
                        }
                        if (entries.isEmpty()) dropped++;
                        else { RegionWriter.write(outDir.resolve(f.getFileName().toString()), rf.rx(), rf.rz(), entries); written++; }
                    }
                    p.done.incrementAndGet();
                }
            }
            results.add(new DimResult(d.key(), keptChunks, removed, stripped, written, dropped));
        }

        if (o.newName() != null && !o.newName().isBlank()) {
            Path ld = out.resolve("level.dat");
            Map<String, Object> root = Nbt.readFile(ld);
            Map<String, Object> data = Nbt.compound(root, "Data");
            (data != null ? data : root).put("LevelName", o.newName().trim());
            Nbt.writeFileGzip(ld, root);
        }

        p.phase = "verifying";
        List<String> problems = verify(w, out, dims, o);
        if (o.upgrade() == null || !problems.isEmpty()) return new Result(out, results, copied[0], problems.isEmpty(), problems, null);

        p.phase = "re-saving kept chunks with the Minecraft " + o.upgrade().version() + " server";
        p.done.set(0);
        p.total.set(0);
        try {
            ServerUpgrade.run(o.upgrade(), out, c -> { p.done.set(c[0]); p.total.set(c[1]); });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        p.phase = "removing old-chunk marks";
        Map<String, Integer> expected = new HashMap<>();
        for (DimResult r : results) expected.put(r.key(), r.keptChunks());
        Map<String, Integer> stripped = finishUpgrade(World.open(out), o.keep(), expected, o.upgrade().dataVersion(), problems);
        results.replaceAll(r -> new DimResult(r.key(), r.keptChunks(), r.removedChunks(), stripped.getOrDefault(r.key(), 0), r.regionFilesWritten(), r.regionFilesDropped()));
        return new Result(out, results, copied[0], problems.isEmpty(), problems, o.upgrade().version());
    }

    /**
     * After the server upgrade: removes the old-chunk marks from every chunk of the regenerated dimensions, in place,
     * and checks that exactly the kept chunks are there ({@code expected}: count per dimension), all saved at
     * {@code dataVersion} or newer. Returns marks removed per dimension.
     */
    public static Map<String, Integer> finishUpgrade(World out, Map<String, Set<Long>> keep, Map<String, Integer> expected, int dataVersion,
                                              List<String> problems) throws IOException {
        Map<String, Integer> stripped = new HashMap<>();
        for (Map.Entry<String, Set<Long>> e : keep.entrySet()) {
            Dimension d = out.dimension(e.getKey());
            if (d == null) { problems.add(e.getKey() + ": dimension missing after the upgrade"); continue; }
            Path dir = d.folder("region");
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (var s = Files.list(dir)) { files = s.filter(x -> RegionFile.coords(x.getFileName().toString()) != null).sorted().toList(); }
            int n = 0, chunks = 0;
            for (Path f : files) {
                List<RegionWriter.Entry> entries = new ArrayList<>();
                int rx, rz;
                try (RegionFile rf = RegionFile.open(f)) {
                    rx = rf.rx();
                    rz = rf.rz();
                    for (int i = 0; i < 1024; i++) {
                        if (!rf.has(i)) continue;
                        int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                        RegionFile.RawChunk raw = rf.raw(i);
                        Map<String, Object> nbt = Nbt.read(RegionFile.decompress(raw));
                        if (!e.getValue().contains(WorldScan.key(cx, cz))) problems.add(e.getKey() + " chunk " + cx + "," + cz + " should be gone");
                        int dv = (int) Nbt.number(nbt, "DataVersion", 0);
                        if (dv < dataVersion) problems.add(e.getKey() + " chunk " + cx + "," + cz + " was not upgraded (data version " + dv + ")");
                        if (nbt.remove("blending_data") != null) { raw = zlib(nbt); n++; }
                        entries.add(new RegionWriter.Entry(i, raw, rf.timestamp(i)));
                    }
                }
                chunks += entries.size();
                if (entries.isEmpty()) continue;
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                RegionWriter.write(tmp, rx, rz, entries);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            if (expected.containsKey(e.getKey()) && chunks != expected.get(e.getKey()))
                problems.add(e.getKey() + ": " + chunks + " chunks after the upgrade, expected " + expected.get(e.getKey()));
            stripped.put(e.getKey(), n);
        }
        return stripped;
    }

    /** Re-reads the output: kept chunks present and identical (or only blending removed), nothing else left. */
    static List<String> verify(World w, Path out, List<Dimension> dims, Options o) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Dimension d : dims) {
            Set<Long> keep = o.keep().get(d.key());
            for (String kind : Dimension.KINDS) {
                Path inDir = d.folder(kind);
                if (!Files.isDirectory(inDir)) continue;
                Path outDir = out.resolve(w.dir.relativize(inDir).toString());
                List<Path> files;
                try (var s = Files.list(inDir)) { files = s.filter(x -> RegionFile.coords(x.getFileName().toString()) != null).toList(); }
                for (Path f : files) {
                    Path g = outDir.resolve(f.getFileName().toString());
                    try (RegionFile a = RegionFile.open(f); RegionFile b = Files.exists(g) ? RegionFile.open(g) : null) {
                        for (int i = 0; i < 1024; i++) {
                            int cx = a.rx() * 32 + (i & 31), cz = a.rz() * 32 + (i >> 5);
                            boolean shouldKeep = a.has(i) && keep.contains(WorldScan.key(cx, cz));
                            boolean has = b != null && b.has(i);
                            if (shouldKeep != has) { problems.add(d.key() + " " + kind + " chunk " + cx + "," + cz + (has ? " should be gone" : " is missing")); continue; }
                            if (!has) continue;
                            RegionFile.RawChunk ra = a.raw(i), rb = b.raw(i);
                            if (ra.compression() == rb.compression() && Arrays.equals(ra.data(), rb.data())) continue;
                            Map<String, Object> na = Nbt.read(RegionFile.decompress(ra)), nb = Nbt.read(RegionFile.decompress(rb));
                            na.remove("blending_data");
                            if (!Arrays.equals(Nbt.write(na), Nbt.write(nb))) problems.add(d.key() + " " + kind + " chunk " + cx + "," + cz + " differs");
                        }
                    } catch (IOException e) {
                        problems.add(d.key() + " " + kind + " " + f.getFileName() + ": " + e.getMessage());
                    }
                    if (problems.size() > 200) return problems;
                }
            }
        }
        return problems;
    }

    /** Same chunk without its blending_data tag (zlib), or the input unchanged when it has none. */
    static RegionFile.RawChunk stripBlending(RegionFile.RawChunk raw) throws IOException {
        Map<String, Object> n = Nbt.read(RegionFile.decompress(raw));
        return n.remove("blending_data") == null ? raw : zlib(n);
    }

    private static RegionFile.RawChunk zlib(Map<String, Object> nbt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos)) { z.write(Nbt.write(nbt)); }
        return new RegionFile.RawChunk(RegionFile.ZLIB, bos.toByteArray(), false);
    }
}
