package io.github.estebansa19.chunkkeeper;

import io.github.estebansa19.chunkkeeper.chunk.Chunk;
import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.nbt.NbtList;
import io.github.estebansa19.chunkkeeper.plan.Applier;
import io.github.estebansa19.chunkkeeper.plan.Selection;
import io.github.estebansa19.chunkkeeper.region.RegionFile;
import io.github.estebansa19.chunkkeeper.region.RegionWriter;
import io.github.estebansa19.chunkkeeper.scan.BlockRules;
import io.github.estebansa19.chunkkeeper.scan.WorldScan;
import io.github.estebansa19.chunkkeeper.web.Json;
import io.github.estebansa19.chunkkeeper.world.World;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Dependency-free test runner: every static method named test* in this class is a test. */
public final class TestRunner {
    public static void main(String[] args) throws Exception {
        int failed = 0, passed = 0;
        for (Method m : TestRunner.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())) continue;
            try {
                m.invoke(null);
                passed++;
                System.out.println("PASS " + m.getName());
            } catch (java.lang.reflect.InvocationTargetException e) {
                failed++;
                System.out.println("FAIL " + m.getName() + ": " + e.getCause());
                e.getCause().printStackTrace(System.out);
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static NbtList list(byte type, Object... items) {
        NbtList l = new NbtList(type);
        l.addAll(Arrays.asList(items));
        return l;
    }

    static byte[] zlib(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos)) { z.write(raw); }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ tests

    static void testNbtRoundTrip() throws Exception {
        Map<String, Object> root = map("b", (byte) 1, "s", (short) 2, "i", 3, "l", 4L, "f", 5f, "d", 6d, "ba", new byte[] { 1, 2 },
            "str", "héllo", "list", list(Nbt.STRING, "a", "b"), "empty", list(Nbt.COMPOUND), "c", map("x", 1), "ia", new int[] { 7 },
            "la", new long[] { 8L });
        Map<String, Object> back = Nbt.read(Nbt.write(root));
        check(Arrays.equals(Nbt.write(root), Nbt.write(back)), "NBT bytes survive a round trip");
        eq("héllo", back.get("str"), "string");
        eq(Nbt.COMPOUND, ((NbtList) back.get("empty")).elementType(), "empty list keeps its type");
    }

    static void testRegionWriteAndRead() throws Exception {
        Path dir = Files.createTempDirectory("ck-region");
        List<RegionWriter.Entry> entries = new ArrayList<>();
        for (int i : new int[] { 0, 5, 1023 }) {
            byte[] nbt = Nbt.write(map("DataVersion", 4790, "xPos", i & 31, "zPos", i >> 5, "Status", "minecraft:full"));
            entries.add(new RegionWriter.Entry(i, new RegionFile.RawChunk(RegionFile.ZLIB, zlib(nbt), false), 1234 + i));
        }
        byte[] big = new byte[1_100_000];
        new Random(1).nextBytes(big);
        Map<String, Object> bigNbt = map("DataVersion", 4790, "junk", big);
        entries.add(new RegionWriter.Entry(7, new RegionFile.RawChunk(RegionFile.NONE, Nbt.write(bigNbt), false), 99));
        Path f = dir.resolve("r.-1.2.mca");
        RegionWriter.write(f, -1, 2, entries);
        check(Files.exists(dir.resolve("c.-25.64.mcc")), "oversized chunk goes to an external .mcc file");
        try (RegionFile rf = RegionFile.open(f)) {
            eq(-1, rf.rx(), "rx from name");
            eq(4, rf.chunkCount(), "chunk count");
            eq(4790, ((Number) rf.nbt(5).get("DataVersion")).intValue(), "chunk 5 readable");
            eq(1234 + 1023, rf.timestamp(1023), "timestamp kept");
            check(!rf.has(1), "absent chunk");
            check(Arrays.equals(big, (byte[]) rf.nbt(7).get("junk")), "external chunk read back");
        }
    }

    static void testLz4() throws Exception {
        byte[] block = { 0x32, 'a', 'b', 'c', 3, 0, 0x10, 'x' }; // "abc" + match(offset 3, len 6) + "x"
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        writeLz4Block(s, 0x20, block, 10);
        writeLz4Block(s, 0x10, "raw!".getBytes(StandardCharsets.US_ASCII), 4);
        writeLz4Block(s, 0x10, new byte[0], 0);
        Map<String, Object> dummy = map("x", 1);
        RegionFile.RawChunk raw = new RegionFile.RawChunk(RegionFile.LZ4, s.toByteArray(), false);
        eq("abcabcabcxraw!", new String(RegionFile.decompress(raw), StandardCharsets.US_ASCII), "LZ4 block stream");
        check(dummy.size() == 1, "sanity");
    }

    private static void writeLz4Block(ByteArrayOutputStream s, int method, byte[] data, int original) {
        s.writeBytes("LZ4Block".getBytes(StandardCharsets.US_ASCII));
        s.write(method);
        for (int v : new int[] { data.length, original, 0 }) { s.write(v & 255); s.write(v >> 8 & 255); s.write(v >> 16 & 255); s.write(v >>> 24); }
        s.writeBytes(data);
    }

    static void testPaletteVariants() {
        NbtList palette = list(Nbt.COMPOUND);
        palette.add(map("", "minecraft:air"));
        palette.add(map("", map("id", "minecraft:stone")));
        palette.add(map("id", "minecraft:oak_stairs", "properties", map("facing", "east")));
        palette.add(map("Name", "minecraft:glass"));
        long[] data = new long[256]; // 4 bits per block, 16 per long
        data[0] = 1L | 2L << 4 | 3L << 8; // blocks 0,1,2 of the section
        Map<String, Object> section = map("Y", (byte) -4, "block_states", map("palette", palette, "data", data),
            "biomes", map("palette", list(Nbt.STRING, "minecraft:plains")));
        Map<String, Object> chunk = map("DataVersion", 5023, "xPos", 0, "zPos", 0, "yPos", -4, "Status", "minecraft:full",
            "sections", list(Nbt.COMPOUND, section));
        Chunk c = new Chunk(0, 0, chunk);
        eq("minecraft:stone", c.block(0, -64, 0), "wrapped compound entry");
        eq("minecraft:oak_stairs", c.block(1, -64, 0), "id/properties entry");
        eq("east", c.blockProps(1, -64, 0).get("facing"), "properties");
        eq("minecraft:glass", c.block(2, -64, 0), "Name entry");
        eq("minecraft:air", c.block(3, -64, 0), "index 0");
        eq("minecraft:plains", c.biome(0, -60, 0), "single biome");
        check(c.isFull(), "full status");
    }

    static void testSelectionFormat() throws Exception {
        Set<Long> s = new HashSet<>();
        for (int i = 0; i < 1024; i++) s.add(WorldScan.key(32 + (i & 31), -32 + (i >> 5))); // region 1;-1 complete
        s.add(WorldScan.key(-1, -1));
        String text = Selection.format(s);
        check(text.contains("1;-1\n"), "full region written as one line");
        check(text.contains("-1;-1;-1;-1\n"), "single chunk line");
        eq(s, Selection.parse(text, null), "round trip");
        Set<Long> universe = new HashSet<>(List.of(WorldScan.key(0, 0), WorldScan.key(1, 0)));
        eq(Set.of(WorldScan.key(1, 0)), Selection.parse("inverted\n0;0;0;0\n", universe), "inverted file");
    }

    static void testJson() {
        Map<String, Object> m = map("a", List.of(1, 2.5, "x\"y"), "b", true, "n", null, "arr", new int[] { 3 });
        String s = Json.write(m);
        Map<String, Object> back = Json.object(s);
        eq(true, back.get("b"), "boolean");
        eq("x\"y", ((List<?>) back.get("a")).get(2), "escaped string");
        eq(2.5, ((List<?>) back.get("a")).get(1), "number");
    }

    static void testBlockRules() {
        check(BlockRules.isPlayerBlock("minecraft:oak_planks"), "planks");
        check(BlockRules.isPlayerBlock("minecraft:white_bed"), "bed");
        check(!BlockRules.isPlayerBlock("minecraft:stone"), "stone");
        check(!BlockRules.isPlayerBlock("minecraft:mossy_stone_bricks"), "mossy stone bricks are generated");
        Map<String, Object> chestWithItems = map("id", "minecraft:chest", "Items", list(Nbt.COMPOUND, map("id", "minecraft:dirt")));
        check(BlockRules.isPlayerBlockEntity(chestWithItems, false), "chest outside structures");
        check(!BlockRules.isPlayerBlockEntity(map("id", "minecraft:dispenser", "Items", list(Nbt.COMPOUND, map("id", "minecraft:arrow"))), true),
            "pre-filled dispenser inside a trial chamber");
        check(!BlockRules.isPlayerBlockEntity(map("id", "minecraft:beehive"), false), "natural bee nest");
        check(BlockRules.isEndPortal("minecraft:end_portal") && !BlockRules.isEndPortal("minecraft:end_portal_frame"), "activated end portal only");
        check(!BlockRules.isPlayerBlockEntity(map("id", "minecraft:chest", "LootTable", "minecraft:chests/simple_dungeon"), false), "unopened loot chest");
        check(BlockRules.isPlayerEntity(map("id", "minecraft:wolf", "Owner", new int[] { 1, 2, 3, 4 }), false), "tamed wolf");
        check(!BlockRules.isPlayerEntity(map("id", "minecraft:villager", "Xp", 0, "VillagerData", map("level", 1)), false), "untouched villager");
    }

    static void testApplyKeepsOnlySelectedChunks() throws Exception {
        Path root = Files.createTempDirectory("ck-apply");
        Path world = root.resolve("World");
        Path ow = world.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(ow.resolve("region"));
        Files.createDirectories(ow.resolve("entities"));
        Nbt.writeFileGzip(world.resolve("level.dat"), map("Data", map("LevelName", "Test", "DataVersion", 5023, "Version", map("Name", "26.3"))));
        Files.writeString(world.resolve("icon.png"), "not really a png");
        for (String kind : List.of("region", "entities")) {
            List<RegionWriter.Entry> e = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Map<String, Object> n = map("DataVersion", 4790, "xPos", i, "zPos", 0, "Status", "minecraft:full", "kind", kind);
                if (i == 1 && kind.equals("region")) n.put("blending_data", map("min_section", -4, "max_section", 20));
                e.add(new RegionWriter.Entry(i, new RegionFile.RawChunk(RegionFile.ZLIB, zlib(Nbt.write(n)), false), 1));
            }
            RegionWriter.write(ow.resolve(kind).resolve("r.0.0.mca"), 0, 0, e);
        }
        World w = World.open(world);
        eq(1, w.dimensions.size(), "one dimension found");
        Set<Long> keep = Set.of(WorldScan.key(1, 0), WorldScan.key(3, 0));
        Path out = root.resolve("World (regenerated)");
        Applier.Progress p = new Applier.Progress();
        Applier.Result r = Applier.run(w, new Applier.Options(out, Map.of("overworld", keep), true, "Test 2"), p);
        check(r.verified(), "verified: " + r.problems());
        eq(2, r.dims().get(0).keptChunks(), "kept chunks");
        eq(2, r.dims().get(0).removedChunks(), "removed chunks");
        eq(1, r.dims().get(0).strippedBlending(), "blending tag removed once");
        try (RegionFile rf = RegionFile.open(out.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"))) {
            check(rf.has(1) && rf.has(3) && !rf.has(0) && !rf.has(2), "only kept chunks in region");
            check(!rf.nbt(1).containsKey("blending_data"), "blending_data stripped");
        }
        try (RegionFile ef = RegionFile.open(out.resolve("dimensions/minecraft/overworld/entities/r.0.0.mca"))) {
            check(ef.has(1) && !ef.has(0), "entities follow the selection");
        }
        eq("Test 2", Nbt.string(Nbt.compound(Nbt.readFile(out.resolve("level.dat")), "Data"), "LevelName"), "renamed");
        check(Files.exists(out.resolve("icon.png")), "other files copied");
        try (RegionFile src = RegionFile.open(ow.resolve("region/r.0.0.mca"))) { eq(4, src.chunkCount(), "source untouched"); }
    }
}
