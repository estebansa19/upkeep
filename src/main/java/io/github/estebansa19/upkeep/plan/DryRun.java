package io.github.estebansa19.upkeep.plan;

import io.github.estebansa19.upkeep.scan.RegionData;
import io.github.estebansa19.upkeep.scan.WorldScan;
import io.github.estebansa19.upkeep.world.Dimension;

import java.io.IOException;
import java.util.*;

/** What applying a plan would do, and what might go wrong, without touching anything. */
public final class DryRun {
    private DryRun() {}

    /** First DataVersion with block-state palettes (17w47a, "the flattening"); older chunks store numeric block ids. */
    static final int DV_1_13 = 1451;

    public static final int ANY_KEEP_REASON =RegionData.R_BUILD | RegionData.R_STORAGE | RegionData.R_MOBS | RegionData.R_PORTAL
        | RegionData.R_PLAYER | RegionData.R_END | RegionData.R_STRUCTURE;

    public record Warning(String level, String dim, int cx, int cz, String text) {}

    public record DimSummary(String key, String label, boolean enabled, int existing, int full, int proto, int keep, int regenerate,
                             int regenerateFull, int regenerateProto, int keptWithBlendingTag, int keptMinDataVersion, int keptMaxDataVersion,
                             int keptBelowMarker) {}

    /** The Minecraft version the copy will be played in. {@code marker}: chunks saved before it get the old-chunk mark (null: unknown). */
    public record Target(String version, Integer marker) {}

    public record Report(List<DimSummary> dims, List<Warning> warnings, int dangerCount, int warnCount, int infoCount, Target target) {}

    /** {@code keep}: dimension key -> chunks to keep, for every dimension that will be regenerated. */
    public static Report run(WorldScan scan, Map<String, Set<Long>> keep, Target target) throws IOException {
        List<DimSummary> dims = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        Map<String, Map<Long, int[]>> portals = new HashMap<>(); // dim -> chunk -> {portalBlocks}
        Map<String, Map<Long, RegionData>> regionOf = new HashMap<>();

        for (Dimension d : scan.world.dimensions) {
            Set<Long> k = keep.get(d.key());
            boolean enabled = k != null;
            Map<Long, Integer> global = scan.globalReasons(d);
            int existing = 0, full = 0, proto = 0, kept = 0, regen = 0, regenFull = 0, regenProto = 0, blendTag = 0, belowMarker = 0;
            int[] sampleOld = null, sampleMarked = null;
            int minDv = Integer.MAX_VALUE, maxDv = 0;
            Map<Long, RegionData> rmap = new HashMap<>();
            Map<Long, int[]> pmap = new HashMap<>();
            Map<String, int[]> structureSplits = new TreeMap<>();
            int waterSeams = 0, broken = 0, unreadable = 0;
            int[] sampleWater = null, sampleBroken = null, sampleUnreadable = null;
            for (int[] r : d.regions()) {
                RegionData rd = scan.region(d, r[0], r[1]);
                for (int i = 0; i < 1024; i++) {
                    if (rd.state[i] == RegionData.NONE) continue;
                    long key = WorldScan.key(rd.cx(i), rd.cz(i));
                    rmap.put(key, rd);
                    existing++;
                    if (rd.state[i] == RegionData.FULL) full++; else proto++;
                    if (rd.portalBlocks[i] > 0) pmap.put(key, new int[] { rd.portalBlocks[i] });
                    boolean keepIt = !enabled || k.contains(key);
                    if (keepIt) {
                        kept++;
                        if (rd.blending[i] != 0) { blendTag++; if (sampleMarked == null) sampleMarked = new int[] { rd.cx(i), rd.cz(i) }; }
                        if (target.marker() != null && rd.dataVersion[i] < target.marker()) { belowMarker++; if (sampleOld == null) sampleOld = new int[] { rd.cx(i), rd.cz(i) }; }
                        if (rd.state[i] == RegionData.FULL) { minDv = Math.min(minDv, rd.dataVersion[i]); maxDv = Math.max(maxDv, rd.dataVersion[i]); }
                        continue;
                    }
                    regen++;
                    if (rd.state[i] == RegionData.FULL) regenFull++; else regenProto++;
                    if (rd.state[i] == RegionData.BROKEN) { if (broken++ == 0) sampleBroken = new int[] { rd.cx(i), rd.cz(i) }; }
                    else if (rd.dataVersion[i] < DV_1_13) { if (unreadable++ == 0) sampleUnreadable = new int[] { rd.cx(i), rd.cz(i) }; }
                    int reasons = rd.reasons[i] | global.getOrDefault(key, 0);
                    if ((reasons & ANY_KEEP_REASON) != 0)
                        warnings.add(new Warning("danger", d.key(), rd.cx(i), rd.cz(i), "Will be regenerated but has " + describe(rd, i, reasons)));
                    else if (rd.inhabitedSec[i] >= 3600)
                        warnings.add(new Warning("warn", d.key(), rd.cx(i), rd.cz(i), "Will be regenerated but players spent " + duration(rd.inhabitedSec[i]) + " near it"));
                }
            }
            // chunks whose contents Upkeep can't see: builds there are never suggested
            if (broken > 0)
                warnings.add(new Warning("warn", d.key(), sampleBroken[0], sampleBroken[1], broken + " damaged chunks can't be read, so Upkeep can't"
                    + " tell whether you built there. They will be regenerated unless you keep them."));
            if (unreadable > 0)
                warnings.add(new Warning("warn", d.key(), sampleUnreadable[0], sampleUnreadable[1], unreadable + " chunks were last saved before"
                    + " Minecraft 1.13, so Upkeep can't see their blocks and never suggests them (the map shows them dark). Check them yourself, or"
                    + " open the world once in a newer version and use Optimize World first. They will be regenerated unless you keep them."));
            if (enabled) {
                // kept chunks next to regenerated ones: water at the seam, structures cut in two
                for (Map.Entry<Long, RegionData> e : rmap.entrySet()) {
                    long key = e.getKey();
                    if (!k.contains(key)) continue;
                    RegionData rd = e.getValue();
                    int cx = WorldScan.keyX(key), cz = WorldScan.keyZ(key), i = RegionData.index(cx, cz);
                    int we = rd.waterEdges[i];
                    int[][] side = { { 1, 0, -1 }, { 2, 0, 1 }, { 4, -1, 0 }, { 8, 1, 0 } };
                    for (int[] s : side)
                        if ((we & s[0]) != 0) {
                            long nk = WorldScan.key(cx + s[1], cz + s[2]);
                            if (rmap.containsKey(nk) && !k.contains(nk)) { waterSeams++; if (sampleWater == null) sampleWater = new int[] { cx, cz }; }
                        }
                    List<String[]> refs = rd.references.get(i);
                    if (refs != null)
                        for (String[] ref : refs) {
                            if (isMinor(ref[0])) continue;
                            long sk = WorldScan.key(Integer.parseInt(ref[1]), Integer.parseInt(ref[2]));
                            if (rmap.containsKey(sk) && !k.contains(sk)) structureSplits.putIfAbsent(ref[0] + " @" + ref[1] + "," + ref[2], new int[] { cx, cz });
                        }
                }
                if (waterSeams > 0)
                    warnings.add(new Warning("info", d.key(), sampleWater[0], sampleWater[1], waterSeams
                        + " kept chunk edges have water or lava next to a regenerated chunk; it may flow into the new terrain"));
                for (Map.Entry<String, int[]> s : structureSplits.entrySet())
                    warnings.add(new Warning("warn", d.key(), s.getValue()[0], s.getValue()[1], "Structure " + s.getKey().replace("minecraft:", "")
                        + " has its start in a regenerated chunk but parts in kept chunks; it will be generated again and may not match"));
            }
            portals.put(d.key(), pmap);
            regionOf.put(d.key(), rmap);
            if (enabled && kept > 0 && d.id.equals("minecraft:overworld")) blendingNotes(d, target, kept, belowMarker, blendTag, sampleOld, sampleMarked, k, warnings);
            dims.add(new DimSummary(d.key(), d.label(), enabled, existing, full, proto, kept, regen, regenFull, regenProto, blendTag,
                minDv == Integer.MAX_VALUE ? 0 : minDv, maxDv, target.marker() == null ? -1 : belowMarker));
        }
        portalLinks(keep, portals, warnings);

        warnings.sort(Comparator.comparingInt((Warning w) -> w.level().equals("danger") ? 0 : w.level().equals("warn") ? 1 : 2));
        int danger = 0, warn = 0, info = 0;
        for (Warning w : warnings) { if (w.level().equals("danger")) danger++; else if (w.level().equals("warn")) warn++; else info++; }
        return new Report(dims, warnings, danger, warn, info, target);
    }

    /** Whether the land regenerated next to the kept Overworld chunks will be blended, and what Sharp borders would take. */
    private static void blendingNotes(Dimension d, Target t, int kept, int belowMarker, int marked, int[] sampleOld, int[] sampleMarked,
                                      Set<Long> keep, List<Warning> warnings) {
        long any = keep.iterator().next();
        int[] at = sampleOld != null ? sampleOld : sampleMarked != null ? sampleMarked : new int[] { WorldScan.keyX(any), WorldScan.keyZ(any) };
        String v = "Minecraft " + t.version();
        if (t.marker() == null)
            warnings.add(new Warning("info", d.key(), at[0], at[1], "Can't tell whether " + v + " marks the kept chunks as old (its game files"
                + " aren't installed). If it does, the regenerated land next to them is blended."));
        else if (belowMarker > 0)
            warnings.add(new Warning("info", d.key(), at[0], at[1], (belowMarker == kept ? "All " : "") + belowMarker + " kept chunks were last saved by"
                + " an older version than " + t.version() + ". " + v + " marks them as old when it loads them, so the regenerated land next to them"
                + " is blended: the old biome continues about 5 chunks in and the ground is smoothed. For sharp borders choose Sharp on the Apply tab."));
        else if (marked > 0)
            warnings.add(new Warning("info", d.key(), at[0], at[1], marked + " kept chunks carry old-chunk marks from an earlier upgrade, so the"
                + " regenerated land next to them is blended. " + v + " won't mark them again, so Sharp on the Apply tab can simply remove the marks."));
    }

    /** Kept portal whose partner (where the game would link it) is regenerated. */
    private static void portalLinks(Map<String, Set<Long>> keep, Map<String, Map<Long, int[]>> portals, List<Warning> warnings) {
        Map<Long, int[]> ow = portals.getOrDefault("overworld", Map.of()), ne = portals.getOrDefault("the_nether", Map.of());
        Set<Long> keepOw = keep.get("overworld"), keepNe = keep.get("the_nether");
        for (long k : ow.keySet()) {
            if (keepOw != null && !keepOw.contains(k)) continue;
            int tx = Math.floorDiv(WorldScan.keyX(k) * 16 + 8, 8) >> 4, tz = Math.floorDiv(WorldScan.keyZ(k) * 16 + 8, 8) >> 4;
            for (int x = tx - 1; x <= tx + 1; x++) for (int z = tz - 1; z <= tz + 1; z++) {
                long nk = WorldScan.key(x, z);
                if (ne.containsKey(nk) && keepNe != null && !keepNe.contains(nk))
                    warnings.add(new Warning("danger", "the_nether", x, z, "Nether portal linked to your kept Overworld portal at chunk "
                        + WorldScan.keyX(k) + "," + WorldScan.keyZ(k) + " would be regenerated"));
            }
        }
        for (long k : ne.keySet()) {
            if (keepNe != null && !keepNe.contains(k)) continue;
            int tx = WorldScan.keyX(k) * 8, tz = WorldScan.keyZ(k) * 8;
            for (int x = tx - 8; x <= tx + 15; x++) for (int z = tz - 8; z <= tz + 15; z++) {
                long ok = WorldScan.key(x, z);
                if (ow.containsKey(ok) && keepOw != null && !keepOw.contains(ok))
                    warnings.add(new Warning("danger", "overworld", x, z, "Overworld portal linked to your kept Nether portal at chunk "
                        + WorldScan.keyX(k) + "," + WorldScan.keyZ(k) + " would be regenerated"));
            }
        }
    }

    /** Structures that are everywhere and rarely matter when cut (mostly underground or underwater). */
    static boolean isMinor(String id) {
        String n = id.replace("minecraft:", "");
        return n.startsWith("mineshaft") || n.startsWith("shipwreck") || n.startsWith("ocean_ruin") || n.startsWith("ruined_portal")
            || n.equals("buried_treasure") || n.equals("nether_fossil");
    }

    static String describe(RegionData rd, int i, int reasons) {
        List<String> p = new ArrayList<>();
        if ((reasons & RegionData.R_BUILD) != 0) p.add(rd.playerBlocks[i] + " player-placed blocks");
        if ((reasons & RegionData.R_STORAGE) != 0)
            p.add(rd.playerBlockEntities[i] + " player block entities" + (rd.containersWithItems[i] > 0 ? " (" + rd.containersWithItems[i] + " containers with items)" : ""));
        if ((reasons & RegionData.R_MOBS) != 0) p.add(rd.playerEntities[i] + " named/tamed/placed entities");
        if ((reasons & RegionData.R_PORTAL) != 0) p.add("a portal");
        if ((reasons & RegionData.R_PLAYER) != 0) p.add("a player position, spawn or respawn point");
        if ((reasons & RegionData.R_END) != 0) p.add("End island / gateway data");
        if ((reasons & RegionData.R_STRUCTURE) != 0) p.add("the start of a structure your build sits in");
        return String.join(", ", p);
    }

    static String duration(int sec) {
        return sec >= 3600 ? (sec / 3600) + " h " + (sec % 3600) / 60 + " min" : (sec / 60) + " min";
    }
}
