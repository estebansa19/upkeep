package io.github.estebansa19.chunkkeeper.scan;

import io.github.estebansa19.chunkkeeper.chunk.Chunk;
import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.nbt.NbtList;
import io.github.estebansa19.chunkkeeper.region.RegionFile;
import io.github.estebansa19.chunkkeeper.render.BlockColors;
import io.github.estebansa19.chunkkeeper.world.Dimension;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Reads one region once: renders its 512x512 top-down image and collects the per-chunk facts. */
final class RegionScanner {
    private RegionScanner() {}

    static final int BUILD_MIN_BLOCKS = 6;
    private static final int VOID = 0xFF0B0B12;

    record Result(RegionData data, int[] argb) {}

    static Result scan(Dimension dim, int rx, int rz, BlockColors colors, StructureIndex structures) throws IOException {
        RegionData d = new RegionData(rx, rz);
        int[] argb = new int[512 * 512];
        int[] height = new int[512 * 512];
        Arrays.fill(height, Integer.MIN_VALUE);
        @SuppressWarnings("unchecked") List<int[]>[] boxesOf = new List[1024];

        Path rp = dim.regionPath("region", rx, rz);
        if (Files.exists(rp)) {
            try (RegionFile rf = RegionFile.open(rp)) {
                for (int idx = 0; idx < 1024; idx++) {
                    if (!rf.has(idx)) continue;
                    int cx = d.cx(idx), cz = d.cz(idx);
                    Chunk c;
                    try {
                        Map<String, Object> nbt = rf.nbt(idx);
                        c = new Chunk(cx, cz, nbt);
                    } catch (IOException | RuntimeException e) {
                        d.state[idx] = RegionData.BROKEN;
                        d.errors.add("chunk " + cx + "," + cz + ": " + e.getMessage());
                        continue;
                    }
                    try {
                        boxesOf[idx] = chunk(c, idx, d, structures);
                        render(c, dim.hasCeiling(), colors, argb, height, idx, d);
                    } catch (RuntimeException e) {
                        d.errors.add("chunk " + cx + "," + cz + ": " + e);
                    }
                }
            }
        }
        Path ep = dim.regionPath("entities", rx, rz);
        if (Files.exists(ep)) {
            try (RegionFile ef = RegionFile.open(ep)) {
                for (int idx = 0; idx < 1024; idx++) {
                    if (!ef.has(idx)) continue;
                    try {
                        Map<String, Object> nbt = ef.nbt(idx);
                        NbtList ents = Nbt.list(nbt, "Entities");
                        if (ents != null) for (Object o : ents) entity(o, idx, d, boxesOf[idx]);
                    } catch (IOException | RuntimeException e) {
                        d.errors.add("entities " + d.cx(idx) + "," + d.cz(idx) + ": " + e.getMessage());
                    }
                }
            }
        }
        shade(argb, height);
        for (int i = 0; i < 1024; i++) {
            int r = 0;
            if (d.playerBlocks[i] >= BUILD_MIN_BLOCKS) r |= RegionData.R_BUILD;
            if (d.playerBlockEntities[i] > 0) r |= RegionData.R_STORAGE;
            if (d.playerEntities[i] > 0) r |= RegionData.R_MOBS;
            if (d.portalBlocks[i] > 0) r |= RegionData.R_PORTAL;
            d.reasons[i] = r;
        }
        return new Result(d, argb);
    }

    /** Facts from the chunk NBT; returns the structure piece boxes that touch this chunk. */
    private static List<int[]> chunk(Chunk c, int idx, RegionData d, StructureIndex structures) {
        d.state[idx] = c.isFull() ? RegionData.FULL : RegionData.PROTO;
        d.dataVersion[idx] = c.dataVersion;
        d.inhabitedSec[idx] = (int) Math.min(Integer.MAX_VALUE, c.inhabitedTime / 20);
        d.blending[idx] = (byte) (c.hasBlendingData() ? 1 : 0);

        int x0 = c.cx * 16, z0 = c.cz * 16;
        List<int[]> boxes = new ArrayList<>();
        Map<String, List<int[]>> starts = c.structureStarts();
        if (!starts.isEmpty()) {
            d.starts.put(idx, new ArrayList<>(starts.keySet()));
            structures.remember(c.cx, c.cz, starts);
        }
        Map<String, List<int[]>> refs = c.structureReferences();
        if (!refs.isEmpty()) {
            List<String[]> l = new ArrayList<>();
            for (Map.Entry<String, List<int[]>> e : refs.entrySet())
                for (int[] s : e.getValue()) {
                    l.add(new String[] { e.getKey(), String.valueOf(s[0]), String.valueOf(s[1]) });
                    for (int[] b : structures.boxes(e.getKey(), s[0], s[1]))
                        if (b[0] - 1 <= x0 + 15 && b[3] + 1 >= x0 && b[2] - 1 <= z0 + 15 && b[5] + 1 >= z0) boxes.add(b);
                }
            d.references.put(idx, l);
        }
        for (List<int[]> bl : starts.values())
            for (int[] b : bl) if (b[0] - 1 <= x0 + 15 && b[3] + 1 >= x0 && b[2] - 1 <= z0 + 15 && b[5] + 1 >= z0) boxes.add(b);

        for (Chunk.Section s : c.sections()) {
            int n = s.paletteSize();
            byte[] kind = null; // 1 player block, 2 portal-ish
            for (int p = 0; p < n; p++) {
                String id = s.paletteId(p);
                byte k = BlockRules.isNetherPortal(id) || BlockRules.isEndPortal(id) ? (byte) 2 : BlockRules.isPlayerBlock(id) ? (byte) 1 : 0;
                if (k != 0) { if (kind == null) kind = new byte[n]; kind[p] = k; }
            }
            if (kind == null) continue;
            for (int i = 0; i < 4096; i++) {
                int k = kind[s.paletteIndex(i)];
                if (k == 0) continue;
                if (k == 2) { d.portalBlocks[idx]++; continue; }
                int x = x0 + (i & 15), y = s.y * 16 + (i >> 8), z = z0 + ((i >> 4) & 15);
                if (inside(boxes, x, y, z)) d.structureBlocks[idx]++; else d.playerBlocks[idx]++;
            }
        }
        for (Map<String, Object> be : c.blockEntities()) {
            int x = (int) Nbt.number(be, "x", x0), y = (int) Nbt.number(be, "y", 0), z = (int) Nbt.number(be, "z", z0);
            if (BlockRules.isPlayerBlockEntity(be, inside(boxes, x, y, z))) d.playerBlockEntities[idx]++;
            if (BlockRules.isContainerWithItems(be)) d.containersWithItems[idx]++;
            if ("minecraft:end_gateway".equals(Nbt.string(be, "id"))) {
                int[] exit = Nbt.pos(be.containsKey("exit_portal") ? be.get("exit_portal") : be.get("ExitPortal"));
                if (exit != null) d.gatewayExits.add(exit);
            }
        }
        for (Map<String, Object> e : c.legacyEntities()) entity(e, idx, d, boxes);
        return boxes;
    }

    private static void entity(Object o, int idx, RegionData d, List<int[]> boxes) {
        if (!(o instanceof Map<?, ?> m)) return;
        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) m;
        int[] p = Nbt.pos(e.get("Pos"));
        boolean inStructure = p != null && boxes != null && inside(boxes, p[0], p[1], p[2]);
        if (BlockRules.isPlayerEntity(e, inStructure)) d.playerEntities[idx]++;
        if (BlockRules.isVillager(e)) d.villagers[idx]++;
        NbtList passengers = Nbt.list(e, "Passengers");
        if (passengers != null) for (Object q : passengers) entity(q, idx, d, boxes);
    }

    /**
     * Inside a structure piece's footprint. Height is ignored and a 1-block margin added on purpose: structures place
     * blocks outside their stored boxes (mineshaft support planks below, chains above, village foundations).
     */
    private static boolean inside(List<int[]> boxes, int x, int y, int z) {
        for (int[] b : boxes) if (x >= b[0] - 1 && x <= b[3] + 1 && z >= b[2] - 1 && z <= b[5] + 1) return true;
        return false;
    }

    private static void render(Chunk c, boolean ceiling, BlockColors colors, int[] argb, int[] height, int idx, RegionData d) {
        int[] hm = c.heightmap("WORLD_SURFACE");
        int minY = c.minY(), maxY = minY;
        for (Chunk.Section s : c.sections()) maxY = Math.max(maxY, s.y * 16 + 15);
        int px0 = (idx & 31) * 16, pz0 = (idx >> 5) * 16;
        int water = 0;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int y = hm != null ? Math.min(hm[lz * 16 + lx], maxY) : maxY;
                if (ceiling) {
                    y = Math.min(y, minY + 110);
                    while (y > minY && !BlockRules.isAirLike(c.block(lx, y, lz))) y--;
                }
                String id = "minecraft:air";
                for (; y >= minY; y--) { id = c.block(lx, y, lz); if (!BlockRules.isAirLike(id)) break; }
                int pi = (pz0 + lz) * 512 + px0 + lx;
                if (y < minY) { argb[pi] = VOID; continue; }
                int rgb = colors.color(id);
                if (BlockRules.isWaterLike(id)) {
                    int depth = 0;
                    for (int yy = y - 1; yy > minY && depth < 16 && BlockRules.isWaterLike(c.block(lx, yy, lz)); yy--) depth++;
                    rgb = shade(rgb, 1.1 - Math.min(depth, 12) * 0.04);
                    if (lz == 0 || lz == 15 || lx == 0 || lx == 15)
                        water |= (lz == 0 ? 1 : 0) | (lz == 15 ? 2 : 0) | (lx == 0 ? 4 : 0) | (lx == 15 ? 8 : 0);
                } else if (id.equals("minecraft:lava") && (lz == 0 || lz == 15 || lx == 0 || lx == 15)) {
                    water |= (lz == 0 ? 1 : 0) | (lz == 15 ? 2 : 0) | (lx == 0 ? 4 : 0) | (lx == 15 ? 8 : 0);
                }
                argb[pi] = 0xFF000000 | rgb;
                height[pi] = y;
            }
        d.waterEdges[idx] = (byte) water;
    }

    private static void shade(int[] argb, int[] height) {
        int[] out = argb.clone();
        for (int z = 0; z < 512; z++)
            for (int x = 0; x < 512; x++) {
                int i = z * 512 + x, h = height[i];
                if (h == Integer.MIN_VALUE) continue;
                int hn = z > 0 && height[i - 512] != Integer.MIN_VALUE ? height[i - 512] : h;
                int hw = x > 0 && height[i - 1] != Integer.MIN_VALUE ? height[i - 1] : h;
                double f = 1 + Math.max(-0.3, Math.min(0.3, ((h - hn) + (h - hw)) * 0.05));
                out[i] = 0xFF000000 | shade(argb[i] & 0xFFFFFF, f);
            }
        System.arraycopy(out, 0, argb, 0, argb.length);
    }

    static int shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), g = (int) Math.min(255, ((rgb >> 8) & 255) * f), b = (int) Math.min(255, (rgb & 255) * f);
        return r << 16 | g << 8 | b;
    }
}
