package uk.toakan.tad.skyblockshoppinglist;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public final class SslCommandManager {

    private SslCommandManager() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(commandTree("ssl"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(String name) {
        return
            literal(name)
                .executes(context -> {
                    context.getSource().sendFeedback(Component.translatable("skyblock-shopping-list.command.help", ShoppingList.NAME)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
                    context.getSource().sendFeedback(Component.translatable("skyblock-shopping-list.command.help.reset")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.translatable("skyblock-shopping-list.command.help.menu")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.translatable("skyblock-shopping-list.command.help.hud")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.translatable("skyblock-shopping-list.command.help.credits")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    return 1;
                })
                .then(literal("reset")
                    .executes(context -> {
                        ShoppingList.debug("Executing complete mod reset");
                        FilePathManager.resetData();
                        ProfileManager.clearCaches();
                        HudStyle.clear();
                        SandboxWidget.getInstance().resetConfiguration();
                        context.getSource().sendFeedback(
                            Component.translatable("skyblock-shopping-list.command.reset", ShoppingList.NAME)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                        );
                        return 1;
                    })
                )
                .then(literal("menu")
                    .executes(context -> {
                        // Deferred to the next tick: the chat screen closes after the command runs.
                        ShoppingListClient.shouldOpenMenu = true;
                        return 1;
                    })
                )
                .then(literal("hud")
                    .executes(context -> {
                        ShoppingListClient.shouldOpenMoveHud = true;
                        return 1;
                    })
                )
                .then(literal("credits")
                    .executes(context -> {
                        context.getSource().sendFeedback(
                            Component.translatable("skyblock-shopping-list.command.credits", ShoppingList.NAME)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                        );
                        context.getSource().sendFeedback(
                            Component.translatable("skyblock-shopping-list.command.credits.data")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                                .append(Component.literal(" • "))
                                .append(Component.literal("https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                        );
                        return 1;
                    })
                );
    }
}