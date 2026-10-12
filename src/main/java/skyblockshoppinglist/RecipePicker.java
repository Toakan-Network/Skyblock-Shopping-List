package skyblockshoppinglist;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The recipe picker on the left of the List, Recipes and Forge tabs: search box, × amount box, filter row (Name/Uses,
 * Type, Rarity, Owned) and the list. The search text and filters carry over between tabs; the amount is per screen.
 */
final class RecipePicker {
    private static final int MOUSE_BUTTON_RIGHT = ShoppingListClient.inputCode("MOUSE_BUTTON_RIGHT");
    static final int ROW_HEIGHT = 20;
    /** Top of the first recipe row. Drawing and clicks both use it. */
    static final int LIST_TOP = 107;
    private static final int LIST_X = MenuTabs.LEFT_X;
    private static final int SCROLLBAR = 6;

    // Shared by every tab, kept until the game closes; not saved.
    private static String searchText = "";
    private static boolean matchUses = false;
    private static TypeFilter typeFilter = TypeFilter.ALL;
    private static RarityFilter rarityFilter = RarityFilter.ALL;
    private static OwnedFilter ownedFilter = OwnedFilter.ALL;

    private final RecipeManager recipeManager = RecipeManager.getInstance();
    private final Font font;
    private final Consumer<Integer> onAmountChanged;
    private int amount;
    private List<String> filtered;
    private int scroll = 0;
    private int rowsShown = 10;
    private int right = 270;
    private EditBox searchField;
    private Button matchButton, typeButton, rarityButton, ownedButton;

    /**
     * @param amount          starting value of the × box
     * @param onAmountChanged told the new amount when the player edits the box (never below 1)
     */
    RecipePicker(Font font, int amount, Consumer<Integer> onAmountChanged) {
        this.font = font;
        this.amount = Math.max(1, amount);
        this.onAmountChanged = onAmountChanged;
        filter();
    }

    /** Adds the search box, × amount box and filter row. Call from the screen's init(). */
    void init(Consumer<AbstractWidget> add, int screenWidth, int screenHeight) {
        // Up to 250px, less on narrow screens; the right column gets the rest.
        int listWidth = Math.max(150, Math.min(250, (screenWidth - 60) / 2));
        right = LIST_X + listWidth;
        rowsShown = Math.max(3, (screenHeight - 40 - LIST_TOP - 15) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, filtered.size() - rowsShown));

        searchField = new EditBox(font, LIST_X, MenuTabs.CONTROLS_Y, listWidth - 54, 20, Component.literal(""));
        searchField.setMaxLength(50);
        searchField.setHint(searchHint());
        searchField.setValue(searchText);
        searchField.setResponder(text -> {
            searchText = text == null ? "" : text;
            filter();
            scroll = 0;
        });
        add.accept(searchField);

        EditBox amountBox = new EditBox(font, right - 40, MenuTabs.CONTROLS_Y, 40, 20, Component.literal("Amount"));
        amountBox.setMaxLength(6);
        amountBox.setValue(String.valueOf(amount));
        amountBox.setResponder(text -> {
            try {
                amount = Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
            onAmountChanged.accept(amount);
        });
        add.accept(amountBox);

        int gap = 2;
        int w = (listWidth - 3 * gap) / 4;
        int x = LIST_X;
        matchButton = filterButton(add, Component.literal(matchUses ? "Uses" : "Name"), x, w,
            "Name: search item names.\nUses: search ingredients, e.g. coal finds Torch.");
        x += w + gap;
        typeButton = filterButton(add, Component.literal(typeFilter.label), x, w,
            "Item type: Armor, Accessory, Pet, Weapon, Tool, Equipment or Other.");
        x += w + gap;
        rarityButton = filterButton(add, Component.literal(rarityFilter.label), x, w, "Rarity: Common to Divine, or Special.");
        x += w + gap;
        ownedButton = filterButton(add, Component.literal(ownedFilter.label), x, right - x,
            "Owned: items you have at least one of (inventory, sacks, storage).\nMissing: items you have none of.");
    }

    private Button filterButton(Consumer<AbstractWidget> add, Component label, int x, int width, String tooltip) {
        Button[] self = new Button[1];
        self[0] = Button.builder(label, button -> cycle(self[0], 1))
            .bounds(x, MenuTabs.FILTER_Y, width, 18)
            .tooltip(Tooltip.create(Component.literal(tooltip)))
            .build();
        add.accept(self[0]);
        return self[0];
    }

    int amount() {
        return amount;
    }

    /** Left edge of the column right of the picker. */
    int rightColumnX() {
        return right + 20;
    }

    /**
     * Draws the title, the "×" by the amount box, and the list. {@code marked} rows (already on the list) and the
     * {@code selected} one are highlighted.
     */
    void render(GuiGraphicsExtractor context, int mouseX, int mouseY, String title, Set<String> marked, String selected) {
        MenuTabs.sectionTitle(context, font, title, LIST_X, MenuTabs.SECTION_Y);
        context.text(font, "×", right - 50, MenuTabs.CONTROLS_Y + 6, MenuTabs.TEXT, false);
        int panelTop = LIST_TOP - 5;
        int panelHeight = rowsShown * ROW_HEIGHT + 10;
        MenuTabs.panel(context, LIST_X, panelTop, right - LIST_X, panelHeight);
        if (filtered.isEmpty()) {
            context.text(font, "No recipes found", LIST_X + 10, LIST_TOP + 5, MenuTabs.TEXT_DIM, false);
            return;
        }
        boolean scrolls = filtered.size() > rowsShown;
        int rowRight = right - 1 - (scrolls ? SCROLLBAR + 2 : 0);
        int end = Math.min(scroll + rowsShown, filtered.size());
        for (int i = scroll; i < end; i++) {
            String recipe = filtered.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_HEIGHT;
            boolean highlighted = recipe.equals(selected) || marked.contains(recipe);
            context.fill(LIST_X + 1, y, rowRight, y + ROW_HEIGHT - 1,
                highlighted ? MenuTabs.SELECTED_BG : i % 2 == 0 ? MenuTabs.ROW_BG : MenuTabs.ROW_BG_ALT);
            if (!highlighted && mouseX >= LIST_X && mouseX < rowRight && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                context.fill(LIST_X + 1, y, rowRight, y + ROW_HEIGHT - 1, MenuTabs.HOVER);
            }
            int color = highlighted ? 0xFFFFFFFF : MenuTabs.TEXT;
            context.text(font, fitPlain(font, recipe, rowRight - LIST_X - 16, color), LIST_X + 8, y + 6, 0xFFFFFFFF, false);
        }
        if (scrolls) {
            int barX = right - 1 - SCROLLBAR;
            int top = LIST_TOP;
            int height = rowsShown * ROW_HEIGHT;
            context.fill(barX, top, barX + SCROLLBAR, top + height, MenuTabs.ROW_BG_ALT);
            int thumb = Math.max(10, height * rowsShown / filtered.size());
            int thumbY = top + scroll * (height - thumb) / (filtered.size() - rowsShown);
            context.fill(barX, thumbY, barX + SCROLLBAR, thumbY + thumb, MenuTabs.BORDER);
        }
    }

    /** The recipe under the mouse, or null. */
    String nameAt(double mouseX, double mouseY) {
        if (mouseX < LIST_X || mouseX > right || mouseY < LIST_TOP || mouseY >= LIST_TOP + rowsShown * ROW_HEIGHT) return null;
        int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
        return index >= 0 && index < filtered.size() ? filtered.get(index) : null;
    }

    /** Right-click on a filter steps it back. True when the click was used. */
    boolean mouseClicked(MouseButtonEvent ctx) {
        if (ctx.button() != MOUSE_BUTTON_RIGHT) return false;
        for (Button button : new Button[] {matchButton, typeButton, rarityButton, ownedButton}) {
            if (button != null && button.isMouseOver(ctx.x(), ctx.y())) {
                button.playDownSound(Minecraft.getInstance().getSoundManager());
                cycle(button, -1);
                return true;
            }
        }
        return false;
    }

    /** Scrolls the list when the mouse is over it. True when the scroll was used. */
    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (mouseX < LIST_X || mouseX > right || mouseY < LIST_TOP - 5 || mouseY > LIST_TOP + rowsShown * ROW_HEIGHT + 5) return false;
        // Wheel down (negative) moves the list down, like vanilla lists.
        int next = Math.max(0, Math.min(filtered.size() - rowsShown, scroll + (verticalAmount < 0 ? 1 : -1)));
        scroll = Math.max(0, next);
        return true;
    }

    private Component searchHint() {
        return Component.literal(matchUses ? "Search ingredients..." : "Search recipes...");
    }

    /** Steps the filter behind {@code button} by {@code step} (1 forward, -1 back), wrapping around, and re-filters. */
    private void cycle(Button button, int step) {
        if (button == matchButton) {
            matchUses = !matchUses;
            button.setMessage(Component.literal(matchUses ? "Uses" : "Name"));
            searchField.setHint(searchHint());
        } else if (button == typeButton) {
            typeFilter = TypeFilter.values()[Math.floorMod(typeFilter.ordinal() + step, TypeFilter.values().length)];
            button.setMessage(Component.literal(typeFilter.label));
        } else if (button == rarityButton) {
            rarityFilter = RarityFilter.values()[Math.floorMod(rarityFilter.ordinal() + step, RarityFilter.values().length)];
            button.setMessage(Component.literal(rarityFilter.label));
        } else if (button == ownedButton) {
            ownedFilter = OwnedFilter.values()[Math.floorMod(ownedFilter.ordinal() + step, OwnedFilter.values().length)];
            button.setMessage(Component.literal(ownedFilter.label));
        } else {
            return;
        }
        filter();
        scroll = 0;
    }

    private void filter() {
        String term = searchText.toLowerCase(Locale.ROOT);
        ResourcesManager resources = ResourcesManager.getInstance();
        filtered = recipeManager.getRecipeNames().stream()
            .filter(name -> term.isEmpty()
                || (matchUses ? recipeManager.usesIngredient(name, term) : name.toLowerCase(Locale.ROOT).contains(term)))
            .filter(name -> typeFilter.matches(recipeManager.getType(name)))
            .filter(name -> rarityFilter.matches(recipeManager.getRarity(name)))
            .filter(name -> ownedFilter == OwnedFilter.ALL
                || (resources.getResourceByName(name) > 0) == (ownedFilter == OwnedFilter.OWNED))
            .collect(Collectors.toList());
    }

    /** {@code text} in the default font, cut with "..." to fit {@code width}. */
    static Component fitPlain(Font font, String text, int width, int color) {
        Style style = Style.EMPTY.withColor(color & 0xFFFFFF);
        return SandboxWidget.fitName(font, t -> Component.literal(t).setStyle(style), text, width);
    }

    /** Groups of NEU item types ("HELMET" also covers "DUNGEON HELMET"); Other is any type in no group. */
    enum TypeFilter {
        ALL("Type"),
        ARMOR("Armor", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"),
        ACCESSORY("Acc.", "ACCESSORY", "HATCESSORY"),
        PET("Pet", "PET"),
        WEAPON("Weapon", "SWORD", "LONGSWORD", "BOW", "WAND"),
        TOOL("Tool", "PICKAXE", "AXE", "DRILL", "SHOVEL", "HOE", "FARMING TOOL", "FISHING ROD"),
        EQUIPMENT("Equip", "NECKLACE", "CLOAK", "BELT", "GLOVES", "BRACELET"),
        OTHER("Other");

        final String label;
        final List<String> types;

        TypeFilter(String label, String... types) {
            this.label = label;
            this.types = List.of(types);
        }

        boolean has(String type) {
            for (String t : types) {
                if (type.equals(t) || type.endsWith(" " + t)) return true;
            }
            return false;
        }

        boolean matches(String type) {
            return switch (this) {
                case ALL -> true;
                case OTHER -> {
                    if (type.isEmpty()) yield false;
                    for (TypeFilter group : values()) if (group.has(type)) yield false;
                    yield true;
                }
                default -> has(type);
            };
        }
    }

    enum RarityFilter {
        ALL("Rarity", ""), COMMON("Com", "COMMON"), UNCOMMON("Unc", "UNCOMMON"), RARE("Rare", "RARE"),
        EPIC("Epic", "EPIC"), LEGENDARY("Leg", "LEGENDARY"), MYTHIC("Myth", "MYTHIC"), DIVINE("Div", "DIVINE"),
        SPECIAL("Spec", "SPECIAL");

        final String label;
        final String rarity;

        RarityFilter(String label, String rarity) {
            this.label = label;
            this.rarity = rarity;
        }

        /** Special also covers Very Special. */
        boolean matches(String r) {
            return this == ALL || r.equals(rarity) || r.endsWith(" " + rarity);
        }
    }

    enum OwnedFilter {
        ALL("All"), MISSING("Missing"), OWNED("Owned");

        final String label;

        OwnedFilter(String label) {
            this.label = label;
        }
    }
}
