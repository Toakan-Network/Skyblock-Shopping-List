package uk.toakan.tad.skyblockshoppinglist;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.io.File;
import java.io.IOException;


public class WelcomeManager {
    private static final File WELCOME_FLAG_FILE = new File(FilePathManager.MOD_DIR, "welcome_shown.txt");
    private static boolean isFirstTimeUser = true;

    public static void initialize() {
        checkFirstTimeUser();
        if (!isFirstTimeUser) {
            return;
        }
        // Shown the first time the player reaches SkyBlock, not on joining any server.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isFirstTimeUser && client.player != null && SkyblockDetector.isOnSkyblock()) {
                checkFirstTimeUser();
                if (isFirstTimeUser) {
                    showWelcomeMessage(client);
                }
            }
        });
    }

    private static void checkFirstTimeUser() {
        isFirstTimeUser = !WELCOME_FLAG_FILE.exists();
    }

    private static void showWelcomeMessage(Minecraft client) {
        if (client.player == null) return;

        String divider = "§6§l" + "=".repeat(40);

        client.player.sendSystemMessage(Component.literal(divider));
        client.player.sendSystemMessage(Component.literal("§b§l" + ShoppingList.NAME).setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)
        ));

        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.intro"));
        client.player.sendSystemMessage(Component.literal(""));

        Style key = Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA);
        MutableComponent commandsText = Component.translatable("skyblock-shopping-list.welcome.keys");
        commandsText.append(Component.translatable("skyblock-shopping-list.welcome.keys.menu",
            Component.literal(ShoppingListClient.openMenuKeyName()).setStyle(key)));
        commandsText.append(Component.translatable("skyblock-shopping-list.welcome.keys.hud",
            Component.literal(ShoppingListClient.moveHudKeyName()).setStyle(key)));
        client.player.sendSystemMessage(commandsText);

        MutableComponent chatCommandsText = Component.translatable("skyblock-shopping-list.welcome.commands").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.GOLD)
        );
        client.player.sendSystemMessage(chatCommandsText);
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.commands.menu"));
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.commands.hud"));
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.commands.reset"));
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.commands.help"));

        client.player.sendSystemMessage(Component.literal(""));
        MutableComponent firstTimeText = Component.translatable("skyblock-shopping-list.welcome.setup").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.LIGHT_PURPLE)
        );
        client.player.sendSystemMessage(firstTimeText);
        MutableComponent warningText = Component.translatable("skyblock-shopping-list.welcome.setup.storage").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.RED)
        );
        client.player.sendSystemMessage(warningText);
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.setup.step1", ShoppingListClient.openMenuKeyName()));
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.setup.step2"));
        client.player.sendSystemMessage(Component.translatable("skyblock-shopping-list.welcome.setup.step3", ShoppingListClient.toggleWidgetKeyName()));

        try {
            WELCOME_FLAG_FILE.createNewFile();
        } catch (IOException e) {
            ShoppingList.LOGGER.error("Failed to create welcome flag file", e);
        }

        isFirstTimeUser = false;
        client.player.sendSystemMessage(Component.literal(divider));
    }
}
