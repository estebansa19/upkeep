package io.github.estebansa19.chunkkeeper.plan;

import io.github.estebansa19.chunkkeeper.scan.WorldScan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A set of chunks to keep, stored in MCA Selector's selection format:
 * "rx;rz" = a whole region, "rx;rz;cx;cz" = one chunk (absolute chunk coordinates), optional first line "inverted".
 */
public final class Selection {
    private Selection() {}

    /** Parses selection text. {@code universe} (existing chunks) is needed only for inverted files. */
    public static Set<Long> parse(String text, Set<Long> universe) throws IOException {
        Set<Long> out = new HashSet<>();
        boolean inverted = false;
        int n = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            n++;
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("inverted")) { inverted = true; continue; }
            String[] p = line.split(";");
            try {
                if (p.length == 2) {
                    int rx = Integer.parseInt(p[0].trim()), rz = Integer.parseInt(p[1].trim());
                    for (int i = 0; i < 1024; i++) out.add(WorldScan.key(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
                } else if (p.length == 4) {
                    out.add(WorldScan.key(Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())));
                } else {
                    throw new IOException("line " + n + ": expected 'rx;rz' or 'rx;rz;cx;cz'");
                }
            } catch (NumberFormatException e) {
                throw new IOException("line " + n + ": not a number");
            }
        }
        if (!inverted) return out;
        if (universe == null) throw new IOException("inverted selection needs the list of existing chunks");
        Set<Long> inv = new HashSet<>(universe);
        inv.removeAll(out);
        return inv;
    }

    /** Writes whole regions as one line when all 1024 chunks are selected. */
    public static String format(Set<Long> chunks) {
        Map<Long, List<Long>> byRegion = new TreeMap<>();
        for (long k : chunks) {
            int cx = WorldScan.keyX(k), cz = WorldScan.keyZ(k);
            byRegion.computeIfAbsent(WorldScan.key(cx >> 5, cz >> 5), x -> new ArrayList<>()).add(k);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, List<Long>> e : byRegion.entrySet()) {
            int rx = WorldScan.keyX(e.getKey()), rz = WorldScan.keyZ(e.getKey());
            if (e.getValue().size() == 1024) { sb.append(rx).append(';').append(rz).append('\n'); continue; }
            List<Long> l = e.getValue();
            l.sort(Comparator.<Long>comparingInt(WorldScan::keyZ).thenComparingInt(WorldScan::keyX));
            for (long k : l) sb.append(rx).append(';').append(rz).append(';').append(WorldScan.keyX(k)).append(';').append(WorldScan.keyZ(k)).append('\n');
        }
        return sb.toString();
    }

    public static Set<Long> load(Path file, Set<Long> universe) throws IOException {
        if (!Files.exists(file)) return new HashSet<>();
        return parse(Files.readString(file, StandardCharsets.UTF_8), universe);
    }

    public static void save(Path file, Set<Long> chunks) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(chunks), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
