package skyblockshoppinglist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names items by their SkyBlock ID instead of their shown name. Hypixel stores the ID as "id" in the
 * item's custom data; the shown name can carry a reforge ("Refined Titanium Drill DR-X355") or other
 * decoration that would not match the recipe name.
 */
public final class ItemIds {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String PET_ID = "PET";
    /** Pet rarities in the order of the NEU pet ID suffix ("BEE;4" = Legendary Bee). */
    private static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    private static volatile Map<String, String> namesById;
    /**
     * SkyBlock ID per stack. Reading it copies the item's whole custom data (26.2 only offers copyTag()), and
     * the inventory is scanned every 2 ticks. ItemStack uses identity equality and Hypixel sends a new stack
     * whenever an item's data changes, so the ID of a stack never goes stale; entries vanish with the stack.
     */
    private static final Map<ItemStack, Info> ID_CACHE = new WeakHashMap<>();
    /** Custom data fields naming the parts installed in a drill, as SkyBlock IDs. */
    private static final List<String> DRILL_PART_KEYS = List.of("drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module");

    /** Gem types and qualities as Hypixel writes them in an item's "gems" data. */
    private static final Set<String> GEMS = Set.of("RUBY", "AMBER", "TOPAZ", "JADE", "SAPPHIRE", "AMETHYST", "JASPER",
        "OPAL", "AQUAMARINE", "CITRINE", "ONYX", "PERIDOT");
    private static final Set<String> QUALITIES = Set.of("ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT");
    /** A gem slot key: "JADE_0", or a mixed slot like "COMBAT_0" whose gem is in "COMBAT_0_gem". */
    private static final Pattern GEM_SLOT = Pattern.compile("([A-Z]+)_\\d+");

    /**
     * What is read from a stack's custom data once: its SkyBlock ID, the IDs of drill parts installed in it, and the
     * names of gems socketed in it ("Fine Jade Gemstone").
     */
    private record Info(String id, List<String> partIds, List<String> gemNames) {}

    private ItemIds() {}

    /** Re-reads the ID table (after the recipe fetch rewrote it). */
    public static void reload() {
        Map<String, String> loaded = JsonFiles.read(FilePathManager.ITEM_NAMES_JSON, MAP_TYPE);
        namesById = loaded != null ? Map.copyOf(loaded) : Map.of();
    }

    /** The item's SkyBlock ID (pets in NEU form), from the cache when this stack was seen before. */
    private static Info cachedInfo(ItemStack stack) {
        synchronized (ID_CACHE) {
            Info info = ID_CACHE.get(stack);
            if (info == null) {
                CompoundTag data = customData(stack);
                info = new Info(skyblockId(data), partIds(data), gemNames(data));
                ID_CACHE.put(stack, info);
            }
            return info;
        }
    }

    private static String cachedId(ItemStack stack) {
        return cachedInfo(stack).id();
    }

    private static Map<String, String> names() {
        Map<String, String> names = namesById;
        if (names == null) {
            reload();
            names = namesById;
        }
        return names;
    }

    /** The recipe name for this item if its SkyBlock ID is known, otherwise its shown name. */
    public static String nameOf(ItemStack stack) {
        String id = cachedId(stack);
        if (!id.isEmpty()) {
            String name = names().get(id);
            if (name != null) return name;
        }
        return stack.getHoverName().getString();
    }

    /**
     * Adds the stack to {@code counts} under {@link #nameOf}, plus what is fitted in it and comes back out on removal:
     * each drill part (engine, fuel tank, upgrade module) and each socketed gem.
     */
    public static void count(ItemStack stack, Map<String, Integer> counts) {
        counts.merge(nameOf(stack), stack.getCount(), Integer::sum);
        Info info = cachedInfo(stack);
        if (!info.partIds().isEmpty()) {
            Map<String, String> names = names();
            for (String partId : info.partIds()) {
                String name = names.get(partId);
                if (name != null) counts.merge(name, stack.getCount(), Integer::sum);
            }
        }
        for (String gem : info.gemNames()) counts.merge(gem, stack.getCount(), Integer::sum);
    }

    /** True for a real SkyBlock item; menu buttons ("Go Back", "Next Page", glass panes) have no ID. */
    public static boolean hasSkyblockId(ItemStack stack) {
        return !cachedId(stack).isEmpty();
    }

    /** True for a SkyBlock pet item. */
    public static boolean isPet(ItemStack stack) {
        // Pets get the NEU form "TYPE;rarity" (only pets get a ';'); one whose info couldn't be read keeps "PET".
        String id = cachedId(stack);
        return id.equals(PET_ID) || id.indexOf(';') >= 0;
    }

    /**
     * The item's SkyBlock ID. Every pet has the ID "PET" with its type and rarity in the "petInfo" JSON, so pets
     * get the NEU form "TYPE;rarity" ("BEE;4") that the recipe tables use.
     */
    private static String skyblockId(CompoundTag data) {
        String id = data.getStringOr("id", "");
        if (!PET_ID.equals(id)) return id;
        try {
            JsonObject info = JsonParser.parseString(data.getStringOr("petInfo", "")).getAsJsonObject();
            int tier = PET_TIERS.indexOf(info.get("tier").getAsString().toUpperCase(Locale.ROOT));
            if (tier >= 0) return info.get("type").getAsString() + ";" + tier;
        } catch (RuntimeException e) {
            // Missing or unexpected petInfo; fall back to the shown name.
        }
        return id;
    }

    /** SkyBlock IDs of the parts installed in a drill ("mithril_drill_engine" -> MITHRIL_DRILL_ENGINE); empty for anything else. */
    private static List<String> partIds(CompoundTag data) {
        List<String> parts = new java.util.ArrayList<>(0);
        for (String key : DRILL_PART_KEYS) {
            String part = data.getStringOr(key, "");
            if (!part.isBlank()) parts.add(part.toUpperCase(Locale.ROOT));
        }
        return parts.isEmpty() ? List.of() : List.copyOf(parts);
    }

    /**
     * Names of the gems socketed in an item, one per slot ("Fine Jade Gemstone"). Slot keys are "JADE_0" (quality as
     * text, or a compound with "quality"); mixed slots ("COMBAT_0", "UNIVERSAL_0") name their gem in "COMBAT_0_gem".
     * Unlocked but empty slots have no key.
     */
    static List<String> gemNames(CompoundTag data) {
        CompoundTag gems = data.getCompoundOrEmpty("gems");
        if (gems.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(0);
        for (String key : gems.keySet()) {
            Matcher m = GEM_SLOT.matcher(key);
            if (!m.matches()) continue;
            String gem = GEMS.contains(m.group(1)) ? m.group(1) : gems.getStringOr(key + "_gem", "");
            String quality = quality(gems.get(key));
            if (GEMS.contains(gem) && QUALITIES.contains(quality)) out.add(title(quality) + " " + title(gem) + " Gemstone");
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static String quality(Tag tag) {
        if (tag == null) return "";
        Optional<CompoundTag> compound = tag.asCompound();
        String quality = compound.isPresent() ? compound.get().getStringOr("quality", "") : tag.asString().orElse("");
        return quality.toUpperCase(Locale.ROOT);
    }

    /** "JADE" -> "Jade". */
    private static String title(String upper) {
        return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }

    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }
}
