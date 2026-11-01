package io.github.estebansa19.chunkkeeper.scan;

import io.github.estebansa19.chunkkeeper.chunk.Chunk;
import io.github.estebansa19.chunkkeeper.region.RegionFile;
import io.github.estebansa19.chunkkeeper.world.Dimension;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure piece boxes looked up from their start chunks (which may sit in another region file).
 * Thread-safe; caches every start chunk it reads.
 */
final class StructureIndex {
    private final Dimension dim;
    private final Map<Long, Map<String, List<int[]>>> starts = new ConcurrentHashMap<>();

    StructureIndex(Dimension dim) { this.dim = dim; }

    /** Piece boxes of structure {@code id} started in chunk (scx, scz); empty when unknown. */
    List<int[]> boxes(String id, int scx, int scz) {
        long key = ((long) scx << 32) ^ (scz & 0xffffffffL);
        Map<String, List<int[]>> m = starts.computeIfAbsent(key, k -> load(scx, scz));
        return m.getOrDefault(id, List.of());
    }

    void remember(int cx, int cz, Map<String, List<int[]>> s) {
        starts.putIfAbsent(((long) cx << 32) ^ (cz & 0xffffffffL), s);
    }

    private Map<String, List<int[]>> load(int scx, int scz) {
        Path p = dim.regionPath("region", Math.floorDiv(scx, 32), Math.floorDiv(scz, 32));
        if (!Files.exists(p)) return Map.of();
        try (RegionFile rf = RegionFile.open(p)) {
            Map<String, Object> nbt = rf.nbt(RegionFile.index(scx, scz));
            return nbt == null ? Map.of() : new Chunk(scx, scz, nbt).structureStarts();
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
