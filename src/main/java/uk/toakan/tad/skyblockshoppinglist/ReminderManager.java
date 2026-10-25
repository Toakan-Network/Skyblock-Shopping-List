package uk.toakan.tad.skyblockshoppinglist;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ReminderManager {
    /** Sack names shown in one message before the rest are summed up as "+N more". */
    private static final int MAX_SACKS_NAMED = 3;
    private static final int STALE_CHECK_INTERVAL = 400; // 20 seconds
    private static final long STALE_AFTER_MS = 60 * 60 * 1000L;
    /** Ticks on SkyBlock before the first stale check, so it doesn't land on top of the join messages. */
    private static final int STALE_GRACE_TICKS = 1200; // 60 seconds
    private static int skyblockTicks = 0;
    /** Epoch ms of the last stale-sack warning; in memory only, so it shows again after a restart. */
    private static long lastStaleWarning = 0;

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !SkyblockDetector.isOnSkyblock()) {
                skyblockTicks = 0;
                return;
            }
            skyblockTicks++;
            if (skyblockTicks >= STALE_GRACE_TICKS && skyblockTicks % STALE_CHECK_INTERVAL == 0) {
                checkStaleSacks();
            }
        });
    }

    /**
     * Names the sacks to open so the list's counts are right. Sent after joining and after a recipe is added or
     * raised, while sacks the list needs are unread.
     */
    public static void promptSacks(List<String> sacks) {
        if (sacks.isEmpty()) return;
        Component message = Component.translatable("skyblock-shopping-list.reminder.prompt",
            Component.literal(sackList(sacks)).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.WHITE);
        Notifier.send(Notifier.Type.SACK_PROMPT, Component.translatable("skyblock-shopping-list.reminder.prompt.title"), message);
    }

    /** "Mining Sack, Combat Sack", or with more than {@link #MAX_SACKS_NAMED} "A, B, C +2 more". */
    private static String sackList(List<String> sacks) {
        String named = String.join(", ", sacks.subList(0, Math.min(MAX_SACKS_NAMED, sacks.size())));
        return sacks.size() > MAX_SACKS_NAMED
            ? I18n.get("skyblock-shopping-list.reminder.more", named, sacks.size() - MAX_SACKS_NAMED) : named;
    }

    /** Warns, at most once an hour, when sacks haven't been opened for an hour and the list isn't empty. */
    private static void checkStaleSacks() {
        SandboxWidget widget = SandboxWidget.getInstance();
        if (Notifier.isOff(Notifier.Type.STALE_SACKS) || widget.getShoppingList().isEmpty()) return;
        long now = System.currentTimeMillis();
        long lastRead = SackReader.getInstance().getLastRead();
        if (now - lastRead < STALE_AFTER_MS || now - lastStaleWarning < STALE_AFTER_MS) return;
        lastStaleWarning = now;

        Component status = lastRead == 0
            ? Component.translatable("skyblock-shopping-list.reminder.stale.never")
            : Component.translatable("skyblock-shopping-list.reminder.stale.last", (now - lastRead) / STALE_AFTER_MS);
        Component message = status.copy().withStyle(ChatFormatting.WHITE)
            .append((widget.getListSacks().isEmpty()
                ? Component.translatable("skyblock-shopping-list.reminder.stale.open_all")
                : Component.translatable("skyblock-shopping-list.reminder.stale.open", sackList(widget.getListSacks())))
                .withStyle(ChatFormatting.YELLOW));
        Notifier.send(Notifier.Type.STALE_SACKS, Component.translatable("skyblock-shopping-list.reminder.stale.title"), message);
    }
}