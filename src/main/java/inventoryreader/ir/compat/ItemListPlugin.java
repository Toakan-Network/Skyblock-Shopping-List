package inventoryreader.ir.compat;

import com.operationpotato.itemlist.api.ExclusionZoneManager;
import com.operationpotato.itemlist.api.Plugin;
import com.operationpotato.itemlist.api.RecipeButtonManager;
import inventoryreader.ir.ItemIds;
import inventoryreader.ir.RecipeManager;
import inventoryreader.ir.SandboxWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Plugin for the SkyBlock Item List mod (mod id "skyblock-item-list"), loaded only by that mod through the
 * "skyblock-item-list" entrypoint, so this is the only class that may touch its API. Adds a "☰" button to its
 * recipes that puts the item on the shopping list, and keeps its item grid off the HUD panels. Client-side only:
 * nothing here sends anything to the server.
 */
public final class ItemListPlugin implements Plugin {
    @Override
    public void registerRecipeButtons(RecipeButtonManager manager) {
        manager.addProvider((recipe, output) -> {
            if (!SandboxWidget.getInstance().isItemListButton() || output.isEmpty()) {
                return Optional.empty();
            }
            String name = recipeName(output);
            if (name == null) return Optional.empty();
            return Optional.of(Button.builder(Component.literal("☰"), button -> add(name))
                .tooltip(Tooltip.create(Component.literal("Add " + name + " to your shopping list")))
                .size(12, 12)
                .build());
        });
    }

    @Override
    public void registerExclusionZones(ExclusionZoneManager manager) {
        // The HUD draws under every screen, so keep the item grid off it wherever the grid shows.
        manager.addProvider(Screen.class, screen -> SandboxWidget.getInstance().getDrawnPanels());
    }

    /** The shopping list's name for the item, or null when the list doesn't know it. */
    private static String recipeName(ItemStack stack) {
        String name = ItemIds.nameOf(stack);
        return RecipeManager.getInstance().getRecipeNames().contains(name) ? name : null;
    }

    private static void add(String name) {
        SandboxWidget widget = SandboxWidget.getInstance();
        SandboxWidget.AddResult result = widget.addToList(name, 1);
        // WAITING already told the player why.
        if (result == SandboxWidget.AddResult.WAITING) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        Component message = result == SandboxWidget.AddResult.FULL
            ? Component.literal("Shopping list full (" + widget.getShoppingList().size() + "/"
                + widget.getMaxRecipes() + ")").setStyle(Style.EMPTY.withColor(ChatFormatting.RED))
            : Component.literal("Added " + name + " to your shopping list").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
        client.player.sendOverlayMessage(message);
    }
}
