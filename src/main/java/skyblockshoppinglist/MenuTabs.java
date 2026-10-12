package skyblockshoppinglist;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/** The menu's shared title bar and tab row, so every tab screen looks and switches the same way. */
public final class MenuTabs {
    public enum Tab {
        SHOPPING_LIST("List"),
        RESOURCES("Resources"),
        RECIPES("Recipes"),
        FORGE("Forge"),
        SETTINGS("Settings");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    public static final int TAB_Y = 24;
    public static final int TAB_HEIGHT = 18;
    /** First y below the header and tab row. */
    public static final int HEADER_BOTTOM = 44;
    private static final int LEFT = 20;
    private static final int GAP = 4;
    private static final int MAX_TAB_WIDTH = 110;
    /** Colours shared by the mod's screens. */
    public static final int GOLD = 0xFFFFB728;
    public static final int HEADER_BG = 0xFF17293A;
    public static final int HEADER_BORDER = 0xFF223344;
    public static final int SCREEN_BG = 0xFF0E0E0E;
    public static final int PANEL_BG = 0xFF232323;
    public static final int ROW_BG = 0xFF303030;
    public static final int ROW_BG_ALT = 0xFF383838;
    public static final int SELECTED_BG = 0xFF5F7FA0;
    public static final int HOVER = 0x32FFFFFF;
    public static final int BORDER = 0xFF555555;
    public static final int TITLE_BG = 0xFF2A4153;
    public static final int TEXT = 0xFFDDDDDD;
    public static final int TEXT_DIM = 0xFFAAAAAA;

    /** Layout every tab shares: section titles, then the controls row, then the filter row; buttons along the bottom. */
    public static final int SECTION_Y = 47;
    public static final int CONTROLS_Y = 55;
    public static final int FILTER_Y = 79;
    public static final int LEFT_X = 20;
    /** Distance of the bottom button row from the bottom of the screen. */
    public static final int BOTTOM_BAR = 30;

    private MenuTabs() {}

    /** Opens the menu on the given tab. */
    public static void open(Tab tab) {
        Screen screen = switch (tab) {
            case SHOPPING_LIST -> new ShoppingListScreen();
            case RESOURCES -> new SandboxViewer(SandboxViewer.Mode.RESOURCES);
            case RECIPES -> new SandboxViewer(SandboxViewer.Mode.RECIPES);
            case FORGE -> new SandboxViewer(SandboxViewer.Mode.FORGE);
            case SETTINGS -> ConfigScreen.create(Minecraft.getInstance().gui.screen());
        };
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** One button per tab; the active tab's button is disabled. */
    public static List<Button> buttons(Tab active, int screenWidth) {
        List<Button> buttons = new ArrayList<>();
        Tab[] tabs = Tab.values();
        int width = tabWidth(screenWidth);
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            Button button = Button.builder(Component.literal(tab.label), b -> open(tab))
                .bounds(LEFT + i * (width + GAP), TAB_Y, width, TAB_HEIGHT)
                .build();
            button.active = tab != active;
            buttons.add(button);
        }
        return buttons;
    }

    /** Title bar, tab strip background and the active-tab underline. Draw before the screen's widgets. */
    public static void renderHeader(GuiGraphicsExtractor context, Font font, int screenWidth, Tab active) {
        context.fill(0, 0, screenWidth, 22, HEADER_BG);
        context.outline(0, 0, screenWidth, 22, HEADER_BORDER);
        context.text(font, Component.literal(ShoppingList.NAME).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            12, 7, GOLD, false);
        context.fill(0, 22, screenWidth, HEADER_BOTTOM, 0xFF131313);
        int width = tabWidth(screenWidth);
        int x = LEFT + active.ordinal() * (width + GAP);
        context.fill(x, HEADER_BOTTOM - 2, x + width, HEADER_BOTTOM, 0xFF5FAF3F);
    }

    /** Screen background and the title bar with tabs; every tab draws this first. */
    public static void renderBackground(GuiGraphicsExtractor context, Font font, int screenWidth, int screenHeight, Tab active) {
        context.fill(0, 0, screenWidth, screenHeight, SCREEN_BG);
        renderHeader(context, font, screenWidth, active);
    }

    /** A bold gold label above a section, at {@link #SECTION_Y} in every tab. */
    public static void sectionTitle(GuiGraphicsExtractor context, Font font, String text, int x, int y) {
        context.text(font, Component.literal(text).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            x, y, GOLD, false);
    }

    /** A content panel: dark fill and a grey frame. */
    public static void panel(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, PANEL_BG);
        context.outline(x, y, width, height, BORDER);
    }

    private static int tabWidth(int screenWidth) {
        int count = Tab.values().length;
        return Math.min(MAX_TAB_WIDTH, (screenWidth - 2 * LEFT - (count - 1) * GAP) / count);
    }
}
