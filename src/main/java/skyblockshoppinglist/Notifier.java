package skyblockshoppinglist;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Sends in-game notifications to chat, a toast or both, as set per type in Settings > Notifications. */
public final class Notifier {
    private Notifier() {}

    public enum Channel {
        OFF, CHAT, TOAST, BOTH;

        boolean chat() { return this == CHAT || this == BOTH; }
        boolean toast() { return this == TOAST || this == BOTH; }
    }

    /** Each notification and the channel it uses until the player picks another. */
    public enum Type {
        SACK_PROMPT(Channel.CHAT), STALE_SACKS(Channel.CHAT), ACHIEVED(Channel.TOAST), READY(Channel.TOAST);

        public final Channel defaultChannel;

        Type(Channel defaultChannel) { this.defaultChannel = defaultChannel; }
    }

    public static boolean isOff(Type type) {
        return SandboxWidget.getInstance().getNotifyChannel(type) == Channel.OFF;
    }

    /** Client-side only; never sent anywhere. Nothing happens outside SkyBlock or when the type is Off. */
    public static void send(Type type, String title, Component message) {
        Channel channel = SandboxWidget.getInstance().getNotifyChannel(type);
        if (channel == Channel.OFF || !SkyblockDetector.isOnSkyblock()) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (channel.chat()) {
                client.gui.hud.getChat().addClientSystemMessage(Component.literal("[" + ShoppingList.NAME + "] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(title + ": ").withStyle(ChatFormatting.WHITE))
                    // White unless the message styles itself, as in the toast; not the prefix's gold.
                    .append(Component.empty().withStyle(ChatFormatting.WHITE).append(message)));
            }
            if (channel.toast()) {
                // A vanilla ID, so toast mods such as FancyNotify recognise it. add() never merges toasts.
                SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal(title).withStyle(ChatFormatting.GOLD), message);
            }
        });
    }
}
