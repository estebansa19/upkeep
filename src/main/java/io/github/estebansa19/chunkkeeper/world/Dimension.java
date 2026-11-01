package io.github.estebansa19.chunkkeeper.world;

import io.github.estebansa19.chunkkeeper.region.RegionFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** One dimension of a world: its folder with region/, entities/ and poi/ subfolders. */
public final class Dimension {
    public static final String[] KINDS = { "region", "entities", "poi" };

    public final String id;      // "minecraft:overworld"
    public final Path dir;       // folder that holds region/ entities/ poi/
    public final String relPath; // dir relative to the world folder ("" for the legacy overworld)

    Dimension(String id, Path dir, String relPath) {
        this.id = id;
        this.dir = dir;
        this.relPath = relPath;
    }

    /** Short name used in file names and URLs: "overworld", "the_nether", or "ns_name" for others. */
    public String key() {
        return id.startsWith("minecraft:") ? id.substring(10) : id.replace(':', '_').replace('/', '_');
    }

    public String label() {
        switch (id) {
            case "minecraft:overworld": return "Overworld";
            case "minecraft:the_nether": return "Nether";
            case "minecraft:the_end": return "The End";
            default: return id;
        }
    }

    /** Nether-like dimensions have a bedrock roof: the map must look below it. */
    public boolean hasCeiling() { return id.equals("minecraft:the_nether"); }

    public Path folder(String kind) { return dir.resolve(kind); }

    /** Region coordinates {rx, rz} of every non-empty r.X.Z.mca in region/. */
    public List<int[]> regions() throws IOException {
        Path rd = folder("region");
        List<int[]> out = new ArrayList<>();
        if (!Files.isDirectory(rd)) return out;
        try (Stream<Path> s = Files.list(rd)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                int[] c = RegionFile.coords(p.getFileName().toString());
                if (c != null && Files.size(p) >= 2L * RegionFile.SECTOR) out.add(c);
            }
        }
        out.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        return out;
    }

    public Path regionPath(String kind, int rx, int rz) { return folder(kind).resolve("r." + rx + "." + rz + ".mca"); }

    @Override
    public String toString() { return id; }
}
