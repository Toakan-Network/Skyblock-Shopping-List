package skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which mobs drop an item and how often, from NEU's mob drop tables. Written by
 * {@link skyblockshoppinglist.recipes.RemoteRecipeFetcher}; lookups go by item name. Also the kill maths for the
 * Mob drops panel.
 */
public final class MobDrops {
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<Source>>>() {}.getType();
    /**
     * Magic Find raises a drop's chance to chance x (1 + Magic Find / 100), only for drops below 5%
     * (https://hypixelskyblock.minecraft.wiki/w/Drop_Chance; confirmed by the user).
     */
    private static final double MAGIC_FIND_LIMIT = 0.05;

    /** One mob that drops the item, and the base chance per kill (0..1). */
    public static final class Source {
        public String mob;
        public double chance;

        public Source(String mob, double chance) {
            this.mob = mob;
            this.chance = chance;
        }
    }

    private static volatile Map<String, List<Source>> drops = Map.of();
    /** Changes every time the table is (re)loaded, so the HUD knows to recompute. */
    private static volatile long version = 0;

    private MobDrops() {}

    /** Re-reads the drop table. */
    public static void reload() {
        Map<String, List<Source>> loaded = JsonFiles.read(FilePathManager.MOB_DROPS_JSON, MAP_TYPE);
        Map<String, List<Source>> out = new HashMap<>();
        if (loaded != null) {
            loaded.forEach((name, sources) -> {
                if (sources != null && !sources.isEmpty()) out.putIfAbsent(ItemNames.normalize(name), List.copyOf(sources));
            });
        }
        drops = Map.copyOf(out);
        version++;
    }

    public static long getVersion() {
        return version;
    }

    /** Every mob that drops {@code item}, guaranteed drops included; empty when no mob does. */
    public static List<Source> sources(String item) {
        return drops.getOrDefault(ItemNames.normalize(item), List.of());
    }

    /**
     * The item's best source (highest chance below 100%), or null when no mob drops it by chance. A guaranteed
     * drop needs no kill estimate.
     */
    public static Source best(String item) {
        for (Source source : drops.getOrDefault(ItemNames.normalize(item), List.of())) {
            if (source.chance < 1) return source;
        }
        return null;
    }

    /**
     * The item's source for a mob named as its nameplate shows it ("Zealot"), or null when that mob doesn't drop it
     * by chance. NEU splits some mobs ("Zealot", "Zealot (Enderchest)"); the plain name wins over a variant.
     */
    public static Source forMob(String item, String mob) {
        if (mob == null) return null;
        Source variant = null;
        for (Source source : drops.getOrDefault(ItemNames.normalize(item), List.of())) {
            if (source.chance >= 1) continue;
            if (source.mob.equalsIgnoreCase(mob)) return source;
            if (variant == null && baseName(source.mob).equalsIgnoreCase(mob)) variant = source;
        }
        return variant;
    }

    /** "Zealot (Enderchest)" -> "Zealot". */
    static String baseName(String neuMob) {
        return neuMob.replaceAll("\\s*\\([^)]*\\)", "").trim();
    }

    /** NEU's chance text ("2%", "40.65%", "0.00001%") as 0..1; null when it isn't a plain percentage. */
    public static Double parsePercent(String text) {
        if (text == null) return null;
        String s = text.replace("%", "").replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            double p = Double.parseDouble(s) / 100;
            return p > 0 && p <= 1 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The chance with {@code magicFind} applied. */
    public static double effectiveChance(double chance, int magicFind) {
        if (chance >= MAGIC_FIND_LIMIT || magicFind <= 0) return chance;
        return Math.min(1, chance * (1 + magicFind / 100.0));
    }

    /** Kills one drop takes on average. */
    public static long expectedKills(double chance) {
        return chance <= 0 ? Long.MAX_VALUE : (long) Math.ceil(1 / chance - 1e-9);
    }

    /** Kills by which a lucky quarter of players have had the drop (25% chance of at least one by then). */
    public static long luckyKills(double chance) {
        if (chance <= 0) return Long.MAX_VALUE;
        if (chance >= 1) return 1;
        return Math.max(1, (long) Math.ceil(Math.log(0.75) / Math.log(1 - chance) - 1e-9));
    }
}
