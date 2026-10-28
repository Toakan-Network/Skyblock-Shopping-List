package uk.toakan.tad.skyblockshoppinglist.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import uk.toakan.tad.huddable.api.HuddableApi;
import uk.toakan.tad.huddable.api.HuddablePlugin;
import uk.toakan.tad.skyblockshoppinglist.SandboxWidget;
import uk.toakan.tad.skyblockshoppinglist.ShoppingList;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hands the HUD panels to Huddable, so its editor moves, scales, resizes and hides them along with the rest of the
 * HUD, and B opens that editor. Each panel starts from its own saved place (its unmoved box in Huddable). Loaded only
 * by Huddable through the "huddable" entrypoint, so this is the only class that may touch its API.
 */
public final class HuddableCompat implements HuddablePlugin {
    @Override
    public void register() {
        try {
            // The widget is only asked for when drawing: Huddable may call this before Shopping List's own init.
            Map<SandboxWidget.Panel, Identifier> ids = new EnumMap<>(SandboxWidget.Panel.class);
            Map<SandboxWidget.Panel, HuddableApi.Spec> specs = new EnumMap<>(SandboxWidget.Panel.class);
            // Every spec first: a Huddable without part of this API fails here, before anything is registered.
            for (SandboxWidget.Panel panel : SandboxWidget.Panel.values()) {
                ids.put(panel, Identifier.fromNamespaceAndPath(ShoppingList.MOD_ID,
                    "panel_" + panel.name().toLowerCase(Locale.ROOT)));
                specs.put(panel, HuddableApi.Spec.of((w, h) -> {
                        SandboxWidget.PanelRect rect = SandboxWidget.getInstance().getPanelRect(panel);
                        return new HuddableApi.Box(rect.x, rect.y, rect.width, rect.height);
                    })
                    // Huddable applies its own Size setting on top, so Shopping List's sizing is left out.
                    .withBaseScale(() -> SandboxWidget.getInstance().panelScale(panel))
                    .resizable(SandboxWidget.minWidth(panel), SandboxWidget.minHeight(panel))
                    .listedWhen(() -> SandboxWidget.activePanels().contains(panel)));
            }
            HuddableApi.ignoreLayer(SandboxWidget.SANDBOX_WIDGET_LAYER);
            for (SandboxWidget.Panel panel : SandboxWidget.Panel.values()) {
                HuddableApi.register(ids.get(panel), label(panel), specs.get(panel));
            }
            boolean frames = hasFrames(ids.get(SandboxWidget.Panel.MAIN));
            SandboxWidget.setPlacer(new SandboxWidget.PanelPlacer() {
                @Override
                public int frameInset(SandboxWidget.Panel panel) {
                    return frames ? HuddableApi.frameInset(ids.get(panel)) : -1;
                }

                @Override
                public void drawFrame(GuiGraphicsExtractor context, SandboxWidget.Panel panel, int width, int height) {
                    if (frames) HuddableApi.drawFrame(context, ids.get(panel), 0, 0, width, height);
                }

                @Override
                public SandboxWidget.Placement place(SandboxWidget.Panel panel) {
                    HuddableApi.Placement p = HuddableApi.placement(ids.get(panel));
                    return p == null ? null : new SandboxWidget.Placement(p.x(), p.y(), p.width(), p.height(), p.scale(), p.hidden());
                }

                @Override
                public void openEditor(net.minecraft.client.gui.screens.Screen parent) {
                    HuddableApi.openEditor(parent);
                }
            });
        } catch (LinkageError e) {
            ShoppingList.LOGGER.warn("This Huddable version doesn't offer what Shopping List needs; its own Move HUD places the panels", e);
        }
    }

    /** True when this Huddable draws frames (0.5.0 on); older ones only place the panels. */
    private static boolean hasFrames(Identifier id) {
        try {
            HuddableApi.frameInset(id);
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** "Shopping List", or "Shopping List: Total" for the panels split off from it. */
    private static Component label(SandboxWidget.Panel panel) {
        if (panel == SandboxWidget.Panel.MAIN) return Component.translatable("skyblock-shopping-list.hud.title");
        return Component.translatable("skyblock-shopping-list.huddable.panel",
            Component.translatable("skyblock-shopping-list.hud." + panel.name().toLowerCase(Locale.ROOT)));
    }
}
