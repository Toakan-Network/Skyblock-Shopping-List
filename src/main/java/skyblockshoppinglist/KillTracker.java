package skyblockshoppinglist;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts kills for the Mob drops panel and reads the player's Magic Find. A kill is a mob the player hit that the
 * server then reports dead; its name comes from the nameplate above it ("[Lv55] Zealot 13,000/13,000" and a
 * heart), and the kill only counts for items that mob drops. A "RARE DROP! Item (+237 Magic Find)" chat line resets
 * that item's count and gives the Magic Find, as does the tab list's "Magic Find:" line. Observes only: never
 * cancels or changes an attack or a message.
 */
public final class KillTracker {
    /**
     * "RARE DROP! Ender Necklace (+237 Magic Find)", "RARE DROP! (16x Twilight Arrow Poison) (+130% Magic Find)"
     * (a star glyph before "Magic"), with an optional "(2)" when it repeats. Also PET DROP!, VERY RARE DROP! and the
     * like. Player chat starts with a name or channel, so it can't match.
     */
    static final Pattern RARE_DROP = Pattern.compile(
        "^[A-Z ]*DROP! (?:\\((?:[\\d,]+x )?([^()]+)\\)|([^()]+?))(?: \\(\\+([\\d,]+)%?[^()]*Magic Find\\))?(?: \\(\\d+\\))?$");
    /** The tab list's Stats widget, "Magic Find: 237" after a star (only while the player shows that stat there). */
    private static final Pattern TAB_MAGIC_FIND = Pattern.compile("^Magic Find:\\D*([\\d,]+)");
    /** The profile menu's Misc Stats lore, "✯ Magic Find 237" (confirmed by the user). */
    private static final Pattern MENU_MAGIC_FIND = Pattern.compile("Magic Find\\D*([\\d,]+)");
    /** Nameplate parts around the mob's name: "[Lv55] " in front, "13,000/13,000" or "50M" and a heart after it. */
    private static final Pattern LEVEL = Pattern.compile("^\\[Lv\\d+]\\s*");
    private static final String HEART = "\u2764";
    private static final Pattern HEALTH = Pattern.compile("\\s*[\\d,.]+[kKmMbB]?(?:/[\\d,.]+[kKmMbB]?)?" + HEART + ".*$");
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** A hit mob that dies later than this is someone else's kill. */
    private static final long HIT_MEMORY_MS = 30_000;

    /** Saved per profile. */
    static final class State {
        Map<String, Integer> kills = new HashMap<>();
        /** The mob each item's kills were last counted from, as its nameplate shows it. */
        Map<String, String> mobs = new HashMap<>();
        int lastMagicFind;
        /** Where {@link #lastMagicFind} was read: "tab list", "profile" or "last drop". */
        String magicFindFrom;
    }

    /** A mob the player hit: when, and its name from the nameplate then. */
    private record Hit(long at, String name) {}

    private static State state;
    private static volatile long version = 0;
    /** Items (normalized names) on the list, still missing, that a mob drops; set by the HUD. */
    private static volatile Set<String> targets = Set.of();
    /** Client thread only: mobs hit recently, by entity id. */
    private static final Map<Integer, Hit> hits = new HashMap<>();
    private static boolean loggedTab = false;
    private static int ticks = 0;

    private KillTracker() {}

    public static void register() {
        // Every message fires exactly one of these: hidden by another mod or shown.
        ClientReceiveMessageEvents.GAME.register(KillTracker::read);
        ClientReceiveMessageEvents.GAME_CANCELED.register(KillTracker::read);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide()) onAttack(entity);
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < CHECK_INTERVAL_TICKS) return;
            ticks = 0;
            if (!HudStyle.get().showMobDrops || !SkyblockDetector.isTracking() || client.level == null) return;
            readTab(client);
            long now = System.currentTimeMillis();
            hits.values().removeIf(hit -> now - hit.at() > HIT_MEMORY_MS);
        });
    }

    private static void onAttack(Entity entity) {
        if (!HudStyle.get().showMobDrops || !SkyblockDetector.isTracking()) return;
        if (!(entity instanceof LivingEntity) || entity instanceof Player || entity instanceof ArmorStand) return;
        hits.put(entity.getId(), new Hit(System.currentTimeMillis(), mobName(entity)));
    }

    /** Called (client thread) when the server reports a living entity dead; see LivingEntityDeathMixin. */
    public static void onDeath(LivingEntity entity) {
        Hit hit = hits.remove(entity.getId());
        if (hit == null || System.currentTimeMillis() - hit.at() > HIT_MEMORY_MS) return;
        String name = mobName(entity);
        if (name == null) name = hit.name();
        ShoppingList.debug("Kill: {}", name);
        if (name != null) addKill(name);
    }

    /** The mob's name from the nameplate armor stand just above it, or null when none is found. */
    private static String mobName(Entity entity) {
        for (ArmorStand stand : entity.level().getEntitiesOfClass(ArmorStand.class,
                entity.getBoundingBox().inflate(0.5, 2.5, 0.5))) {
            Component custom = stand.getCustomName();
            if (custom == null) continue;
            String name = nameplateName(ItemNames.clean(custom.getString()));
            if (name != null) return name;
        }
        return null;
    }

    /** "[Lv55] Zealot 13,000/13,000" plus a heart -> "Zealot"; null when the text isn't a mob nameplate. */
    static String nameplateName(String plate) {
        if (!plate.contains(HEART)) return null;
        String name = HEALTH.matcher(LEVEL.matcher(plate).replaceFirst("")).replaceFirst("");
        // Symbols in front (a skull and the like) mark the mob type.
        name = name.replaceFirst("^[^\\p{L}]+", "").trim();
        return name.isEmpty() ? null : name;
    }

    private static void read(Component message, boolean overlay) {
        if (overlay || !HudStyle.get().showMobDrops || !SkyblockDetector.isTracking()) return;
        Matcher m = RARE_DROP.matcher(ItemNames.clean(message.getString()));
        if (!m.matches()) return;
        String item = m.group(1) != null ? m.group(1) : m.group(2);
        Integer magicFind = m.group(3) == null ? null : parseInt(m.group(3));
        ShoppingList.debug("Drop read: {} (Magic Find {})", item, magicFind);
        dropped(item.trim(), magicFind);
    }

    private static void readTab(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String line = ItemNames.clean(name.getString());
            Matcher m = TAB_MAGIC_FIND.matcher(line);
            if (!m.find()) continue;
            if (!loggedTab) {
                loggedTab = true;
                ShoppingList.debug("Magic Find from tab list: {}", line);
            }
            Integer magicFind = parseInt(m.group(1));
            if (magicFind != null) setMagicFind(magicFind);
            return;
        }
    }

    private static Integer parseInt(String digits) {
        try {
            return Integer.parseInt(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sets which items kills count towards (normalized names). */
    public static void setTargets(Set<String> items) {
        targets = Set.copyOf(items);
    }

    /** One kill of {@code mob} for every target item it drops. */
    private static synchronized void addKill(String mob) {
        State s = load();
        boolean counted = false;
        for (String item : targets) {
            if (MobDrops.forMob(item, mob) == null) continue;
            s.kills.merge(item, 1, Integer::sum);
            s.mobs.put(item, mob);
            counted = true;
        }
        if (counted) changed();
    }

    private static synchronized void dropped(String item, Integer magicFind) {
        State s = load();
        boolean change = s.kills.remove(ItemNames.normalize(item)) != null;
        if (magicFind != null && (magicFind != s.lastMagicFind || !"last drop".equals(s.magicFindFrom))) {
            s.lastMagicFind = magicFind;
            s.magicFindFrom = "last drop";
            change = true;
        }
        if (change) changed();
    }

    private static synchronized void setMagicFind(int magicFind) {
        State s = load();
        if (s.lastMagicFind == magicFind && "tab list".equals(s.magicFindFrom)) return;
        s.lastMagicFind = magicFind;
        s.magicFindFrom = "tab list";
        changed();
    }

    /** The profile menu ("Your SkyBlock Profile", confirmed by the user), whose Misc Stats item lists Magic Find. */
    public static boolean isProfileMenu(String title) {
        return title.contains("Your SkyBlock Profile");
    }

    /**
     * Reads Magic Find from the open profile menu's Misc Stats item. Only that item: the equipped armor shown in
     * the same menu can carry its own "Magic Find" bonus line.
     */
    public static void readProfileMenu(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        for (net.minecraft.world.item.ItemStack stack : MenuSlots.containerStacks(menu)) {
            if (!ItemNames.clean(stack.getHoverName().getString()).contains("Misc Stats")) continue;
            net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = ItemNames.clean(line.getString());
                Matcher m = MENU_MAGIC_FIND.matcher(text);
                if (!m.find()) continue;
                Integer magicFind = parseInt(m.group(1));
                if (magicFind == null) continue;
                ShoppingList.debug("Magic Find from profile menu: {}", text);
                synchronized (KillTracker.class) {
                    State s = load();
                    if (s.lastMagicFind == magicFind && "profile".equals(s.magicFindFrom)) return;
                    s.lastMagicFind = magicFind;
                    s.magicFindFrom = "profile";
                    changed();
                }
                return;
            }
        }
    }

    /** "Magic Find: 237 (last drop)": the value the estimates use and where it came from. */
    public static synchronized String magicFindLabel() {
        int set = HudStyle.get().magicFind;
        if (set > 0) return I18n.get("skyblock-shopping-list.magic_find.from", set, I18n.get("skyblock-shopping-list.magic_find.setting"));
        State s = load();
        if (s.magicFindFrom == null && s.lastMagicFind <= 0) return I18n.get("skyblock-shopping-list.magic_find.unread");
        if (s.magicFindFrom == null) return I18n.get("skyblock-shopping-list.magic_find.value", s.lastMagicFind);
        String from = switch (s.magicFindFrom) {
            case "last drop" -> I18n.get("skyblock-shopping-list.magic_find.last_drop");
            case "tab list" -> I18n.get("skyblock-shopping-list.magic_find.tab_list");
            default -> s.magicFindFrom;
        };
        return I18n.get("skyblock-shopping-list.magic_find.from", s.lastMagicFind, from);
    }

    /** Kills since {@code item} last dropped. */
    public static synchronized int kills(String item) {
        return load().kills.getOrDefault(ItemNames.normalize(item), 0);
    }

    /** The mob {@code item}'s kills were last counted from, or null. */
    public static synchronized String mob(String item) {
        return load().mobs.get(ItemNames.normalize(item));
    }

    /** The Magic Find setting, or the last one read from the game when that is 0. */
    public static synchronized int magicFind() {
        int set = HudStyle.get().magicFind;
        return set > 0 ? set : load().lastMagicFind;
    }

    /** Clears every kill count (Settings button). */
    public static synchronized void resetAll() {
        State s = load();
        s.kills.clear();
        s.mobs.clear();
        changed();
    }

    public static long getVersion() {
        return version;
    }

    /** Forgets the in-memory copy (profile switch or reset). */
    public static synchronized void clear() {
        state = null;
        version++;
    }

    private static State load() {
        if (state == null) {
            State loaded = JsonFiles.read(FilePathManager.killTrackerJson(), State.class);
            state = loaded != null ? loaded : new State();
            if (state.kills == null) state.kills = new HashMap<>();
            if (state.mobs == null) state.mobs = new HashMap<>();
        }
        return state;
    }

    private static void changed() {
        version++;
        State s = new State();
        s.kills = new HashMap<>(state.kills);
        s.mobs = new HashMap<>(state.mobs);
        s.lastMagicFind = state.lastMagicFind;
        s.magicFindFrom = state.magicFindFrom;
        JsonFiles.writeAsync(FilePathManager.killTrackerJson(), () -> s);
    }
}
