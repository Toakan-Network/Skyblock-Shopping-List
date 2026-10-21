package skyblockshoppinglist;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts kills for the Mob drops panel and reads the player's Magic Find. A kill is a "+12 Combat (...)" part in
 * the action bar; a "RARE DROP! Item (+237 ✯ Magic Find)" chat line resets that item's count and gives the
 * Magic Find, as does the tab list's "Magic Find:" line. Observes messages only; never cancels or edits them.
 */
public final class KillTracker {
    /** "+12.5 Combat (1,234/5,000)": Hypixel resends the action bar, so the same text is the same kill. */
    private static final Pattern COMBAT = Pattern.compile("\\+[\\d,.]+ Combat \\([^)]*\\)");
    /**
     * "RARE DROP! Ender Necklace (+237 ✯ Magic Find)", "RARE DROP! (16x Twilight Arrow Poison) (+130% ✯ Magic
     * Find)", with an optional "(2)" when it repeats. Also PET DROP!, VERY RARE DROP! and the like. Player chat
     * starts with a name or channel, so it can't match.
     */
    static final Pattern RARE_DROP = Pattern.compile(
        "^[A-Z ]*DROP! (?:\\((?:[\\d,]+x )?([^()]+)\\)|([^()]+?))(?: \\(\\+([\\d,]+)%?[^()]*Magic Find\\))?(?: \\(\\d+\\))?$");
    /** The tab list's Stats widget, "Magic Find: ✯237" (only while the player shows that stat there). */
    private static final Pattern TAB_MAGIC_FIND = Pattern.compile("^Magic Find:\\D*([\\d,]+)");
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Saved per profile. */
    static final class State {
        Map<String, Integer> kills = new HashMap<>();
        int lastMagicFind;
    }

    private static State state;
    private static volatile long version = 0;
    /** Items (normalized names) on the list, still missing, that a mob drops; set by the HUD. */
    private static volatile Set<String> targets = Set.of();
    private static String lastCombat;
    private static boolean loggedTab = false;
    private static int ticks = 0;

    private KillTracker() {}

    public static void register() {
        // Every message fires exactly one of these: hidden by another mod or shown.
        ClientReceiveMessageEvents.GAME.register(KillTracker::read);
        ClientReceiveMessageEvents.GAME_CANCELED.register(KillTracker::read);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < CHECK_INTERVAL_TICKS) return;
            ticks = 0;
            if (!HudStyle.get().showMobDrops || !SkyblockDetector.isTracking() || client.level == null) return;
            readTab(client);
        });
    }

    private static void read(Component message, boolean overlay) {
        if (!HudStyle.get().showMobDrops || !SkyblockDetector.isTracking()) return;
        String text = ItemNames.clean(message.getString());
        if (overlay) {
            Matcher m = COMBAT.matcher(text);
            if (!m.find()) return;
            String combat = m.group();
            if (combat.equals(lastCombat)) return;
            lastCombat = combat;
            ShoppingList.debug("Kill from action bar: {}", text);
            addKill();
        } else {
            Matcher m = RARE_DROP.matcher(text);
            if (!m.matches()) return;
            String item = m.group(1) != null ? m.group(1) : m.group(2);
            Integer magicFind = m.group(3) == null ? null : parseInt(m.group(3));
            ShoppingList.debug("Drop read: {} (Magic Find {})", item, magicFind);
            dropped(item.trim(), magicFind);
        }
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

    private static synchronized void addKill() {
        Set<String> now = targets;
        if (now.isEmpty()) return;
        State s = load();
        for (String item : now) s.kills.merge(item, 1, Integer::sum);
        changed();
    }

    private static synchronized void dropped(String item, Integer magicFind) {
        State s = load();
        boolean change = s.kills.remove(ItemNames.normalize(item)) != null;
        if (magicFind != null && magicFind != s.lastMagicFind) {
            s.lastMagicFind = magicFind;
            change = true;
        }
        if (change) changed();
    }

    private static synchronized void setMagicFind(int magicFind) {
        State s = load();
        if (s.lastMagicFind == magicFind) return;
        s.lastMagicFind = magicFind;
        changed();
    }

    /** Kills since {@code item} last dropped. */
    public static synchronized int kills(String item) {
        return load().kills.getOrDefault(ItemNames.normalize(item), 0);
    }

    /** The Magic Find setting, or the last one read from the game when that is 0. */
    public static synchronized int magicFind() {
        int set = HudStyle.get().magicFind;
        return set > 0 ? set : load().lastMagicFind;
    }

    /** Clears every kill count (Settings button). */
    public static synchronized void resetAll() {
        load().kills.clear();
        changed();
    }

    public static long getVersion() {
        return version;
    }

    /** Forgets the in-memory copy (profile switch or reset). */
    public static synchronized void clear() {
        state = null;
        lastCombat = null;
        version++;
    }

    private static State load() {
        if (state == null) {
            State loaded = JsonFiles.read(FilePathManager.killTrackerJson(), State.class);
            state = loaded != null ? loaded : new State();
            if (state.kills == null) state.kills = new HashMap<>();
        }
        return state;
    }

    private static void changed() {
        version++;
        State s = new State();
        s.kills = new HashMap<>(state.kills);
        s.lastMagicFind = state.lastMagicFind;
        JsonFiles.writeAsync(FilePathManager.killTrackerJson(), () -> s);
    }
}
