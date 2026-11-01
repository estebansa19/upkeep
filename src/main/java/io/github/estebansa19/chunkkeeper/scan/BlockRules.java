package io.github.estebansa19.chunkkeeper.scan;

import io.github.estebansa19.chunkkeeper.nbt.Nbt;
import io.github.estebansa19.chunkkeeper.nbt.NbtList;

import java.util.*;

/**
 * Heuristics that tell player-made things from world generation. They err on the side of "player made":
 * a false positive only means an extra chunk is suggested for keeping.
 */
public final class BlockRules {
    private BlockRules() {}

    private static final Set<String> PLAYER_EXACT = Set.of(
        "glass", "glass_pane", "tinted_glass", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch",
        "lantern", "soul_lantern", "rail", "powered_rail", "detector_rail", "activator_rail", "redstone_wire", "repeater", "comparator",
        "piston", "sticky_piston", "piston_head", "observer", "dispenser", "dropper", "hopper", "crafter", "chest", "trapped_chest", "barrel",
        "furnace", "blast_furnace", "smoker", "crafting_table", "anvil", "chipped_anvil", "damaged_anvil", "enchanting_table", "bookshelf",
        "chiseled_bookshelf", "lectern", "brewing_stand", "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "composter",
        "loom", "cartography_table", "fletching_table", "smithing_table", "stonecutter", "grindstone", "beacon", "conduit", "ladder", "scaffolding",
        "flower_pot", "note_block", "jukebox", "target", "lightning_rod", "daylight_detector", "tripwire_hook", "lever", "redstone_lamp", "tnt",
        "ender_chest", "respawn_anchor", "lodestone", "iron_block", "gold_block", "diamond_block", "emerald_block", "netherite_block",
        "lapis_block", "redstone_block", "coal_block", "bricks", "hay_block", "slime_block", "honey_block", "iron_bars", "chain",
        "end_rod", "sea_lantern", "decorated_pot", "copper_bulb", "beehive", "campfire", "soul_campfire", "bell", "smooth_stone",
        "quartz_block", "polished_andesite", "polished_diorite", "polished_granite", "dirt_path", "farmland", "jack_o_lantern",
        "carved_pumpkin", "nether_portal", "moving_piston");

    private static final String[] PLAYER_SUFFIX = {
        "_planks", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor", "_wool", "_carpet", "_bed", "_stained_glass",
        "_stained_glass_pane", "_concrete", "_concrete_powder", "_glazed_terracotta", "_sign", "_wall_sign", "_hanging_sign", "_wall_hanging_sign",
        "_banner", "_wall_banner", "_button", "_pressure_plate", "_shulker_box", "_candle", "_bricks", "_wall", "_lantern", "_bars",
        "_chain", "_bulb", "_grate", "_shelf" };

    private static final Set<String> NOT_PLAYER = Set.of(
        "mossy_stone_bricks", "cracked_stone_bricks", "infested_stone_bricks", "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
        "mud_bricks", "cobblestone_wall", "mossy_cobblestone_wall", "nether_brick_fence", "red_nether_bricks", "cracked_nether_bricks",
        "end_stone_bricks", "purpur_stairs", "purpur_slab", "prismarine_bricks", "dark_prismarine", "polished_blackstone_bricks",
        "cracked_polished_blackstone_bricks", "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "polished_blackstone_brick_wall",
        "deepslate_bricks", "deepslate_tiles", "cracked_deepslate_bricks", "cracked_deepslate_tiles", "chiseled_deepslate", "tuff_bricks",
        "chiseled_tuff_bricks", "tuff_brick_stairs", "tuff_brick_slab", "tuff_brick_wall");

    /** True for blocks that world generation (outside structures) basically never places. */
    public static boolean isPlayerBlock(String id) {
        if (!id.startsWith("minecraft:")) return false;
        String n = id.substring(10);
        if (NOT_PLAYER.contains(n)) return false;
        if (PLAYER_EXACT.contains(n)) return true;
        for (String s : PLAYER_SUFFIX) if (n.endsWith(s)) return true;
        return false;
    }

    public static boolean isNetherPortal(String id) { return id.equals("minecraft:nether_portal"); }

    /** An activated End portal: strongholds generate single eyes by chance, but never all twelve. */
    public static boolean isEndPortal(String id) { return id.equals("minecraft:end_portal"); }

    public static boolean isAirLike(String id) {
        switch (id) {
            case "minecraft:air": case "minecraft:cave_air": case "minecraft:void_air": case "minecraft:light": case "minecraft:barrier":
            case "minecraft:structure_void":
                return true;
            default:
                return false;
        }
    }

    public static boolean isWaterLike(String id) {
        switch (id) {
            case "minecraft:water": case "minecraft:bubble_column": case "minecraft:seagrass": case "minecraft:tall_seagrass":
            case "minecraft:kelp": case "minecraft:kelp_plant":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ block entities

    private static final Set<String> CONTAINERS = Set.of("chest", "trapped_chest", "barrel", "hopper", "dispenser", "dropper", "furnace",
        "blast_furnace", "smoker", "brewing_stand", "crafter", "decorated_pot", "chiseled_bookshelf", "lectern", "jukebox", "shelf");
    private static final Set<String> ALWAYS_PLAYER_BE = Set.of("beacon", "conduit", "ender_chest", "comparator", "daylight_detector",
        "command_block", "structure_block", "enchanting_table", "shulker_box");
    // natural bee nests use the "beehive" block entity too, so it is not listed here
    private static final Set<String> PLAYER_UNLESS_STRUCTURE_BE = Set.of("banner", "bed", "skull", "campfire", "bell");

    /**
     * Whether a block entity looks player made. {@code inStructure}: its position lies inside a structure piece.
     * Containers inside structures never count: some generate pre-filled without a loot table (trial chamber dispensers).
     */
    public static boolean isPlayerBlockEntity(Map<String, Object> be, boolean inStructure) {
        String id = Nbt.string(be, "id");
        if (id == null) return false;
        String n = id.replace("minecraft:", "");
        if (ALWAYS_PLAYER_BE.contains(n)) return true;
        if (n.equals("sign") || n.equals("hanging_sign")) return signHasText(be);
        if (CONTAINERS.contains(n)) return !inStructure && !be.containsKey("LootTable");
        if (PLAYER_UNLESS_STRUCTURE_BE.contains(n)) return !inStructure;
        return false;
    }

    public static boolean isContainerWithItems(Map<String, Object> be) {
        String id = Nbt.string(be, "id");
        if (id == null) return false;
        String n = id.replace("minecraft:", "");
        return (CONTAINERS.contains(n) || n.equals("shulker_box") || n.endsWith("shulker_box")) && hasItems(be);
    }

    static boolean hasItems(Map<String, Object> be) {
        NbtList items = Nbt.list(be, "Items");
        if (items != null && !items.isEmpty()) return true;
        return be.get("item") instanceof Map<?, ?> || be.get("RecordItem") instanceof Map<?, ?> || be.get("Book") instanceof Map<?, ?>;
    }

    static boolean signHasText(Map<String, Object> be) {
        for (String side : List.of("front_text", "back_text")) {
            NbtList msgs = Nbt.list(Nbt.compound(be, side), "messages");
            if (msgs != null) for (Object m : msgs) if (!isEmptyText(m)) return true;
        }
        for (String k : List.of("Text1", "Text2", "Text3", "Text4")) if (be.get(k) != null && !isEmptyText(be.get(k))) return true;
        return false;
    }

    private static boolean isEmptyText(Object m) {
        if (m instanceof String s) {
            String t = s.trim();
            return t.isEmpty() || t.equals("\"\"") || t.equals("''") || t.equals("{\"text\":\"\"}") || t.equals("{text:\"\"}");
        }
        if (m instanceof Map<?, ?> c) {
            Object t = c.get("text");
            return (t == null || String.valueOf(t).isEmpty()) && !c.containsKey("extra") && !c.containsKey("translate");
        }
        return m == null;
    }

    // ------------------------------------------------------------------ entities

    private static final Set<String> PLAYER_PLACED_ENTITIES = Set.of("item_frame", "glow_item_frame", "armor_stand", "painting",
        "minecart", "hopper_minecart", "furnace_minecart", "tnt_minecart", "spawner_minecart", "command_block_minecart", "block_display",
        "item_display", "text_display", "interaction", "leash_knot", "snow_golem", "copper_golem");

    /** Player-related entity: named, tamed, leashed, traded with, built, or placed by a player. */
    public static boolean isPlayerEntity(Map<String, Object> e, boolean inStructure) {
        String id = Nbt.string(e, "id");
        if (id == null) return false;
        String n = id.replace("minecraft:", "");
        if (e.containsKey("CustomName")) return true;
        if (e.containsKey("Owner") || e.containsKey("owner")) return true;
        if (e.containsKey("leash") || e.containsKey("Leash")) return true;
        if (n.equals("iron_golem") && Nbt.number(e, "PlayerCreated", 0) != 0) return true;
        if (n.equals("villager") || n.equals("zombie_villager")) {
            Map<String, Object> vd = Nbt.compound(e, "VillagerData");
            return Nbt.number(e, "Xp", 0) > 0 || (vd != null && Nbt.number(vd, "level", 1) > 1);
        }
        if (n.endsWith("_boat") || n.endsWith("_raft") || n.endsWith("_chest_boat") || n.endsWith("_chest_raft")) return true;
        if (n.equals("chest_minecart")) return !e.containsKey("LootTable") && !inStructure;
        if (PLAYER_PLACED_ENTITIES.contains(n)) return !inStructure;
        return false;
    }

    public static boolean isVillager(Map<String, Object> e) {
        String id = Nbt.string(e, "id");
        return "minecraft:villager".equals(id);
    }
}
