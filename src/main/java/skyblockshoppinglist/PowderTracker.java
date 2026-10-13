package skyblockshoppinglist;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the "Mithril Powder", "Gemstone Powder" and "Glacite Powder" resources equal to the tab list's Powders
 * widget (lines such as "Mithril: 102,455"), so powder can be a goal on the shopping list. The widget only shows
 * while the player has it enabled; the last value read is kept until then. Read-only: nothing is sent or clicked.
 */
public final class PowderTracker {
    /** The resource names, which {@link RecipeManager} also offers as items to add to the list. */
    public static final List<String> NAMES = List.of("Mithril Powder", "Gemstone Powder", "Glacite Powder");
    private static final Pattern LINE = Pattern.compile("^(Mithril|Gemstone|Glacite):\\s*([\\d,]+)$");
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static final Map<String, Integer> lastWritten = new HashMap<>();
    private static int ticks = 0;

    private PowderTracker() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < CHECK_INTERVAL_TICKS) return;
            ticks = 0;
            if (!SkyblockDetector.isTracking() || client.level == null) return;
            read(client);
        });
    }

    /** Forgets the last values (after a reset or a profile switch), so the next read is written again. */
    public static void clear() {
        lastWritten.clear();
    }

    private static void read(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            Matcher m = LINE.matcher(ItemNames.clean(name.getString()));
            if (!m.find()) continue;
            int amount;
            try {
                amount = Integer.parseInt(m.group(2).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String resource = m.group(1) + " Powder";
            Integer previous = lastWritten.put(resource, amount);
            if (previous == null || previous != amount) {
                ResourcesManager.getInstance().setResourceAmount(resource, amount);
            }
        }
    }
}
