package io.github.estebansa19.chunkkeeper.render;

import io.github.estebansa19.chunkkeeper.game.GameVersions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Top-down map colors. When a Minecraft client jar is available the colors are the average of each block's
 * texture (so new blocks get colors without code changes); otherwise a small built-in table is used.
 */
public final class BlockColors {
    private static final int GRASS = 0x91BD59, FOLIAGE = 0x77AB2F, WATER = 0x3F76E4;

    private final Map<String, Integer> textures;           // texture name -> average RGB
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();
    public final String source;

    private BlockColors(Map<String, Integer> textures, String source) {
        this.textures = textures;
        this.source = source;
    }

    public static BlockColors builtIn() { return new BlockColors(Map.of(), "built-in colors"); }

    /** Colors from the client jar, cached as a small text file in {@code cacheDir}. */
    public static BlockColors fromClientJar(Path jar, Path cacheDir) {
        Path cached = cacheDir.resolve("colors-" + jar.getFileName().toString().replace(".jar", "") + ".txt");
        Map<String, Integer> tex = new HashMap<>();
        try {
            if (Files.isRegularFile(cached)) {
                for (String line : Files.readAllLines(cached, StandardCharsets.UTF_8)) {
                    int sp = line.indexOf(' ');
                    if (sp > 0) tex.put(line.substring(0, sp), Integer.parseInt(line.substring(sp + 1), 16));
                }
                return new BlockColors(tex, "textures from " + jar.getFileName());
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("assets/minecraft/textures/block/") || !n.endsWith(".png")) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        BufferedImage img = ImageIO.read(in);
                        if (img != null) {
                            int avg = average(img);
                            if (avg != -1) tex.put(n.substring(32, n.length() - 4), avg);
                        }
                    }
                }
            }
            Files.createDirectories(cacheDir);
            StringBuilder sb = new StringBuilder();
            new TreeMap<>(tex).forEach((k, v) -> sb.append(k).append(' ').append(String.format("%06x", v)).append('\n'));
            Files.writeString(cached, sb.toString(), StandardCharsets.UTF_8);
            return new BlockColors(tex, "textures from " + jar.getFileName());
        } catch (IOException e) {
            return builtIn();
        }
    }

    /** Average of the first animation frame (top square), weighted by alpha; -1 if fully transparent. */
    private static int average(BufferedImage img) {
        int w = img.getWidth(), h = Math.min(img.getHeight(), w);
        long r = 0, g = 0, b = 0, a = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y), al = p >>> 24;
                if (al < 16) continue;
                r += (long) ((p >> 16) & 255) * al; g += (long) ((p >> 8) & 255) * al; b += (long) (p & 255) * al; a += al;
            }
        if (a == 0) return -1;
        return (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    /** Looks for a client jar matching {@code version} in common launcher folders, else the newest one found. */
    public static Path findClientJar(String version) { return GameVersions.findClientJar(version); }

    /** RGB for a block id such as "minecraft:oak_stairs". */
    public int color(String id) {
        Integer c = cache.get(id);
        if (c == null) {
            c = resolve(id.startsWith("minecraft:") ? id.substring(10) : id.substring(id.indexOf(':') + 1));
            cache.put(id, c);
        }
        return c;
    }

    private int resolve(String n) {
        int tint = tint(n);
        Integer t = textures.isEmpty() ? null : texture(n);
        if (t != null) return tint == 0 ? t : multiply(t, tint);
        return fallback(n);
    }

    private Integer texture(String n) {
        switch (n) {
            case "water": case "bubble_column": return textures.get("water_still");
            case "lava": return textures.get("lava_still");
            case "grass_block": return textures.get("grass_block_top");
            case "tall_grass": return textures.get("tall_grass_top");
            case "large_fern": return textures.get("large_fern_top");
            case "kelp": return textures.get("kelp_plant");
            case "tall_seagrass": return textures.get("tall_seagrass_top");
            case "snow": return textures.get("snow");
            default: break;
        }
        List<String> c = new ArrayList<>();
        c.add(n + "_top");
        c.add(n);
        String base = n;
        for (String suf : new String[] { "_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign", "_stairs", "_slab", "_fence_gate", "_fence",
            "_wall", "_pressure_plate", "_button", "_wall_banner", "_banner", "_bed", "_carpet", "_pane", "_wall_torch", "_wall_fan", "_fan" }) {
            if (n.endsWith(suf)) { base = n.substring(0, n.length() - suf.length()); break; }
        }
        if (n.startsWith("potted_")) base = "flower_pot";
        if (n.endsWith("_carpet") || n.endsWith("_bed") || n.endsWith("_banner")) { c.add(base + "_wool"); if (base.equals("moss")) c.add("moss_block"); }
        if (!base.equals(n)) {
            c.add(base + "_top"); c.add(base); c.add(base + "s"); c.add(base + "_planks"); c.add(base + "_block"); c.add(base + "_block_top");
            if (base.endsWith("_brick")) c.add(base + "s");
            if (base.endsWith("_wood") || base.endsWith("_hyphae")) c.add(base.replaceAll("_(wood|hyphae)$", "_log"));
        }
        if (n.endsWith("_wood") || n.endsWith("_hyphae")) c.add(n.replaceAll("_wood$", "_log").replaceAll("_hyphae$", "_stem"));
        c.add(n + "_side");
        c.add(n + "_front");
        c.add(n + "_0");
        c.add(n + "_stage0");
        for (String k : c) { Integer v = textures.get(k); if (v != null) return v; }
        return null;
    }

    /** Biome-tinted blocks get a fixed tint (grayscale textures otherwise). */
    private static int tint(String n) {
        if (n.equals("water") || n.equals("bubble_column") || n.equals("water_cauldron")) return WATER;
        if (n.equals("spruce_leaves")) return 0x619961;
        if (n.equals("birch_leaves")) return 0x80A755;
        if (n.equals("lily_pad")) return 0x208030;
        if (n.equals("grass_block") || n.equals("short_grass") || n.equals("tall_grass") || n.equals("fern") || n.equals("large_fern")
            || n.equals("sugar_cane") || n.equals("potted_fern") || n.equals("bush")) return GRASS;
        if (n.endsWith("_leaves") && !n.startsWith("cherry") && !n.contains("azalea") && !n.startsWith("pale_oak")) return FOLIAGE;
        if (n.equals("vine") || n.equals("attached_melon_stem") || n.equals("melon_stem") || n.equals("pumpkin_stem")) return FOLIAGE;
        return 0;
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return r << 16 | g << 8 | bl;
    }

    private static int fallback(String n) {
        if (n.equals("water") || n.equals("bubble_column") || n.contains("seagrass") || n.startsWith("kelp")) return WATER;
        if (n.equals("lava")) return 0xD8641E;
        if (n.contains("ice")) return 0x9DB8F0;
        if (n.equals("cherry_leaves")) return 0xE9A5C9;
        if (n.equals("spruce_leaves")) return 0x3A5A3A;
        if (n.equals("birch_leaves")) return 0x7FA755;
        if (n.contains("leaves")) return 0x4E8A34;
        if (n.equals("grass_block") || n.contains("grass") || n.contains("fern")) return 0x75B24F;
        if (n.contains("sand")) return n.startsWith("red") ? 0xBE6A2A : 0xDCD3A0;
        if (n.contains("snow") || n.equals("powder_snow")) return 0xF2F6F8;
        if (n.contains("dirt") || n.equals("podzol") || n.equals("mud") || n.equals("farmland")) return 0x8C6A42;
        if (n.equals("gravel")) return 0x8A847E;
        if (n.contains("terracotta")) return 0xA25E40;
        if (n.contains("netherrack") || n.contains("nether_wart") || n.contains("crimson")) return 0x7A2E2E;
        if (n.contains("warped")) return 0x2A7A72;
        if (n.contains("soul")) return 0x51402F;
        if (n.contains("basalt") || n.contains("blackstone") || n.contains("deepslate")) return 0x4A4A50;
        if (n.contains("end_stone")) return 0xDDE0A5;
        if (n.contains("purpur")) return 0xA97DA9;
        if (n.contains("obsidian")) return 0x1E1430;
        if (n.contains("log") || n.contains("_wood") || n.contains("stem")) return 0x6B5133;
        if (n.contains("planks") || n.contains("door") || n.contains("fence") || n.contains("stairs") || n.contains("slab")) return 0xA4844E;
        if (n.contains("glass")) return 0xBFDDEB;
        if (n.contains("wool") || n.contains("carpet")) return 0xEDEDED;
        if (n.contains("brick")) return 0x9C503C;
        if (n.contains("stone") || n.equals("andesite") || n.equals("diorite") || n.equals("granite") || n.equals("tuff") || n.equals("calcite")) return 0x8A8A8A;
        if (n.contains("glowstone") || n.contains("shroomlight")) return 0xE8C060;
        return 0x8A8A7A;
    }
}
