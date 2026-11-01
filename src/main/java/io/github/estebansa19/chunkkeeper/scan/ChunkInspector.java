package io.github.estebansa19.chunkkeeper.scan;

import io.github.estebansa19.chunkkeeper.chunk.Chunk;
import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.nbt.NbtList;
import io.github.estebansa19.chunkkeeper.region.RegionFile;
import io.github.estebansa19.chunkkeeper.world.Dimension;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Human-readable details of one chunk for the side panel. */
public final class ChunkInspector {
    private ChunkInspector() {}

    public static Map<String, Object> inspect(Dimension d, int cx, int cz) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cx", cx);
        out.put("cz", cz);
        int idx = RegionFile.index(cx, cz);
        Path rp = d.regionPath("region", Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        Map<String, Object> nbt = null;
        if (Files.exists(rp)) try (RegionFile rf = RegionFile.open(rp)) { nbt = rf.nbt(idx); }
        if (nbt == null) { out.put("exists", false); return out; }
        Chunk c = new Chunk(cx, cz, nbt);
        out.put("exists", true);
        out.put("status", c.status);
        out.put("dataVersion", c.dataVersion);
        out.put("inhabitedSeconds", c.inhabitedTime / 20);
        out.put("blendingTag", c.hasBlendingData());
        int[] hm = c.heightmap("WORLD_SURFACE");
        if (hm != null) out.put("biome", c.biome(8, hm[8 * 16 + 8], 8));

        Map<String, Integer> blocks = new HashMap<>();
        for (Chunk.Section s : c.sections()) {
            boolean any = false;
            for (int p = 0; p < s.paletteSize(); p++) if (BlockRules.isPlayerBlock(s.paletteId(p))) { any = true; break; }
            if (!any) continue;
            for (int i = 0; i < 4096; i++) {
                String id = s.blockId(i);
                if (BlockRules.isPlayerBlock(id)) blocks.merge(id.replace("minecraft:", ""), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> top = new ArrayList<>();
        blocks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
            .forEach(e -> top.add(Map.of("id", e.getKey(), "count", e.getValue())));
        out.put("playerLikeBlocks", top);

        List<Map<String, Object>> bes = new ArrayList<>();
        for (Map<String, Object> be : c.blockEntities()) {
            if (bes.size() >= 40) break;
            String id = Optional.ofNullable(Nbt.string(be, "id")).orElse("?").replace("minecraft:", "");
            boolean player = BlockRules.isPlayerBlockEntity(be, false);
            boolean items = BlockRules.isContainerWithItems(be);
            if (!player && !items && !id.equals("end_gateway")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("pos", new int[] { (int) Nbt.number(be, "x", 0), (int) Nbt.number(be, "y", 0), (int) Nbt.number(be, "z", 0) });
            m.put("items", items);
            m.put("loot", be.containsKey("LootTable"));
            bes.add(m);
        }
        out.put("blockEntities", bes);

        List<Map<String, Object>> ents = new ArrayList<>();
        Path ep = d.regionPath("entities", Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        List<Object> list = new ArrayList<>(c.legacyEntities());
        if (Files.exists(ep)) try (RegionFile ef = RegionFile.open(ep)) {
            Map<String, Object> en = ef.nbt(idx);
            NbtList l = Nbt.list(en, "Entities");
            if (l != null) list.addAll(l);
        }
        Map<String, Integer> otherMobs = new TreeMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mm)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) mm;
            String id = Optional.ofNullable(Nbt.string(e, "id")).orElse("?").replace("minecraft:", "");
            if (!BlockRules.isPlayerEntity(e, false) && !BlockRules.isVillager(e)) { otherMobs.merge(id, 1, Integer::sum); continue; }
            if (ents.size() >= 40) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            Object name = e.get("CustomName");
            if (name != null) m.put("name", name instanceof String s ? s : Nbt.string(name, "text"));
            m.put("pos", Nbt.pos(e.get("Pos")));
            m.put("tamed", e.containsKey("Owner") || e.containsKey("owner"));
            ents.add(m);
        }
        out.put("entities", ents);
        out.put("otherEntities", otherMobs);

        Map<String, List<int[]>> starts = c.structureStarts();
        out.put("structureStarts", new ArrayList<>(starts.keySet()));
        List<String> refs = new ArrayList<>();
        c.structureReferences().forEach((id, l) -> l.forEach(s -> refs.add(id.replace("minecraft:", "") + " (starts in chunk " + s[0] + "," + s[1] + ")")));
        out.put("structureParts", refs);
        return out;
    }
}
