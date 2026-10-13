package skyblockshoppinglist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Menu tab "Shopping List": search recipes and click to add them, edit amounts or remove entries, and see
 * the same trees the HUD shows.
 */
public class ShoppingListScreen extends Screen {
    private final SandboxWidget widget;
    private final RecipeManager recipeManager;
    /** Search, filters and the recipe list on the left; its × box is how many of a recipe one click adds. */
    private RecipePicker picker;
    /** Recipe names the shopping-list rows were built for; rows are rebuilt when the list changes. */
    private List<String> builtRows = List.of();
    /** {@link #builtRows} as a set, for the per-frame "already on the list" check. */
    private java.util.Set<String> builtRowSet = java.util.Set.of();
    private static final int PANEL_TOP = 58;
    private static final int PANEL_ROW = 22;
    private static final int PANEL_MAX_ROWS = 5;
    private int treeViewX = 300;
    private int treeViewY = 80;
    private int treeViewWidth = 400;
    /** Room left for each shopping list row's name once its buttons are placed. */
    private int listNameWidth = 276;
    private int treeViewHeight = 300;
    private int treeScrollOffset = 0;
    private static final int RECIPE_LEVEL_INDENT = 10;
    private static final int GOLD = MenuTabs.GOLD;
    /** Saved lists column, right of the preview; 0 wide (hidden) when the screen has no room for it. */
    private int savedX;
    private int savedWidth;
    private static final int SAVED_TOP = 80;
    private int savedRowsShown;
    private int savedScroll = 0;
    private String saveName = "";
    /** Saved list whose × was clicked once; a second click deletes it. */
    private String pendingDelete;

    public ShoppingListScreen() {
        super(Component.literal(ShoppingList.NAME));
        this.widget = SandboxWidget.getInstance();
        this.recipeManager = RecipeManager.getInstance();
    }

    @Override
    protected void init() {
        MenuTabs.buttons(MenuTabs.Tab.SHOPPING_LIST, width).forEach(this::addRenderableWidget);
        initRecipeTab();
        addRenderableWidget(Button.builder(hudLabel(), button -> {
            widget.setEnabled(!widget.isEnabled());
            button.setMessage(hudLabel());
        }).bounds(MenuTabs.LEFT_X, height - MenuTabs.BOTTOM_BAR, 150, 20).build());
    }

    private Component hudLabel() {
        return Component.literal("HUD: " + (widget.isEnabled() ? "ON" : "OFF"));
    }

    private void initRecipeTab() {
        if (picker == null) picker = new RecipePicker(font, 1, amount -> {});
        picker.init(this::addRenderableWidget, width, height);
        treeViewX = picker.rightColumnX();

        // Shopping list panel (right column, above the preview): name, move up/down, amount box and remove
        // button per row. Order is priority: rows higher up get shared stock first.
        List<ShoppingListEntry> list = widget.getShoppingList();
        treeViewWidth = Math.max(120, Math.min(400, width - treeViewX - 20));
        // Narrow screens: shrink the move buttons, then drop them, so the name keeps some room.
        int moveWidth = treeViewWidth >= 200 ? 20 : treeViewWidth >= 164 ? 12 : 0;
        // Row, right to left: × (-26), amount (-70), Have total / Add more (-88), ▼, ▲.
        int modeX = -88;
        int controlsLeft = moveWidth > 0 ? -modeX + 4 + 2 * moveWidth : -modeX;
        listNameWidth = treeViewWidth - controlsLeft - 8;
        int rows = Math.min(list.size(), PANEL_MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            ShoppingListEntry entry = list.get(i);
            int y = PANEL_TOP + i * PANEL_ROW;
            EditBox amount = new EditBox(font, treeViewX + treeViewWidth - 70, y, 40, 18, Component.literal("Amount"));
            amount.setMaxLength(6);
            amount.setValue(String.valueOf(entry.amount));
            amount.setResponder(text -> {
                try {
                    int value = Integer.parseInt(text.trim());
                    if (value > 0) widget.setEntryAmount(entry.recipe, value);
                } catch (NumberFormatException ignored) {
                    // Keep the saved amount while the box is empty or half-typed.
                }
            });
            addRenderableWidget(amount);
            boolean total = entry.isHaveTotal();
            addRenderableWidget(Button.builder(Component.literal(total ? "=" : "+"), button -> {
                widget.setEntryHaveTotal(entry.recipe, !total);
                rebuildWidgets();
            }).bounds(treeViewX + treeViewWidth + modeX, y, 16, 18)
              .tooltip(Tooltip.create(Component.literal(total
                  ? "Have total (=): the amount is how many you want in total; what you hold counts.\nClick for Add more."
                  : "Add more (+): the amount is how many more to make on top of what you hold.\nClick for Have total.")))
              .build());
            if (moveWidth > 0) {
                Button up = Button.builder(Component.literal("▲"), button -> {
                    widget.moveEntry(entry.recipe, -1);
                    rebuildWidgets();
                }).bounds(treeViewX + treeViewWidth - controlsLeft, y, moveWidth, 18)
                  .tooltip(Tooltip.create(Component.literal("Move up: higher rows get shared materials first.")))
                  .build();
                up.active = i > 0;
                addRenderableWidget(up);
                Button down = Button.builder(Component.literal("▼"), button -> {
                    widget.moveEntry(entry.recipe, 1);
                    rebuildWidgets();
                }).bounds(treeViewX + treeViewWidth + modeX - 2 - moveWidth, y, moveWidth, 18)
                  .tooltip(Tooltip.create(Component.literal("Move down: lower rows get what is left.")))
                  .build();
                down.active = i < list.size() - 1;
                addRenderableWidget(down);
            }
            addRenderableWidget(Button.builder(Component.literal("×"), button -> {
                widget.removeFromList(entry.recipe);
                rebuildWidgets();
            }).bounds(treeViewX + treeViewWidth - 26, y, 20, 18).build());
        }
        builtRows = list.stream().map(e -> e.recipe).toList();
        builtRowSet = java.util.Set.copyOf(builtRows);
        treeViewY = PANEL_TOP + Math.max(1, rows) * PANEL_ROW + 22;
        // Room under the preview for the NPC price line.
        treeViewHeight = Math.max(60, height - 52 - treeViewY);

        addRenderableWidget(Button.builder(Component.literal("Clear list"), button -> {
            widget.clearList();
            rebuildWidgets();
        }).bounds(width / 2 - 60, height - MenuTabs.BOTTOM_BAR, 120, 20).build());
        initSavedLists(list);
    }

    /**
     * Saved lists column: a name box, Save and Paste (import a share code), then one row per saved list (click to
     * load it, Copy for its share code, × to delete).
     */
    private void initSavedLists(List<ShoppingListEntry> list) {
        savedX = treeViewX + treeViewWidth + 20;
        savedWidth = Math.min(200, width - savedX - 20);
        if (savedWidth < 150) {
            savedWidth = 0;
            return;
        }
        EditBox nameBox = new EditBox(font, savedX, 55, savedWidth - 88, 20, Component.literal("List name"));
        nameBox.setMaxLength(SavedLists.NAME_LIMIT);
        nameBox.setHint(Component.literal("List name..."));
        nameBox.setValue(saveName);
        Button save = Button.builder(Component.literal("Save"), button -> {
            String name = saveName.trim();
            // Read now: amounts typed since the rows were built are in the list but not in {@code list}.
            List<ShoppingListEntry> current = widget.getShoppingList();
            if (name.isEmpty() || current.isEmpty()) return;
            SavedLists.save(name, current);
            pendingDelete = null;
            rebuildWidgets();
        }).bounds(savedX + savedWidth - 84, 55, 40, 20)
          .tooltip(Tooltip.create(Component.literal("Save the shopping list under this name. A saved list with the same name is replaced.")))
          .build();
        Button paste = Button.builder(Component.literal("Paste"), button -> {
            SavedLists.Import imported = SavedLists.importCode(Minecraft.getInstance().keyboardHandler.getClipboard());
            if (imported == null) {
                HudPresets.toast("No list code", "The clipboard doesn't hold a saved list code with known recipes.");
                return;
            }
            HudPresets.toast("Saved list imported", "\"" + imported.name() + "\": " + imported.added() + " recipe(s)"
                + (imported.unknown() > 0 ? ", " + imported.unknown() + " unknown left out" : ""));
            pendingDelete = null;
            rebuildWidgets();
        }).bounds(savedX + savedWidth - 40, 55, 40, 20)
          .tooltip(Tooltip.create(Component.literal("Save a list from a share code on the clipboard. Your shopping list is not changed.")))
          .build();
        save.active = !saveName.isBlank() && !list.isEmpty();
        nameBox.setResponder(text -> {
            saveName = text;
            save.active = !text.isBlank() && !list.isEmpty();
        });
        addRenderableWidget(nameBox);
        addRenderableWidget(save);
        addRenderableWidget(paste);

        List<String> names = SavedLists.names();
        savedRowsShown = Math.max(1, (height - 40 - SAVED_TOP) / PANEL_ROW);
        savedScroll = Math.max(0, Math.min(savedScroll, names.size() - savedRowsShown));
        int end = Math.min(names.size(), savedScroll + savedRowsShown);
        for (int i = savedScroll; i < end; i++) {
            String name = names.get(i);
            int y = SAVED_TOP + (i - savedScroll) * PANEL_ROW;
            addRenderableWidget(Button.builder(fitPlain(name, savedWidth - 70, 0xFFFFFFFF), button -> loadSaved(name))
                .bounds(savedX, y, savedWidth - 60, 18)
                .tooltip(Tooltip.create(Component.literal("Load \"" + name + "\": replaces the shopping list.")))
                .build());
            addRenderableWidget(Button.builder(Component.literal("Copy"), button -> {
                String code = SavedLists.code(name);
                if (code == null) return;
                Minecraft.getInstance().keyboardHandler.setClipboard(code);
                HudPresets.toast("List code copied", "Paste it anywhere to share \"" + name + "\".");
            }).bounds(savedX + savedWidth - 56, y, 32, 18)
              .tooltip(Tooltip.create(Component.literal("Copy \"" + name + "\" as a share code.")))
              .build());
            boolean armed = name.equals(pendingDelete);
            addRenderableWidget(Button.builder(Component.literal(armed ? "?" : "×"), button -> {
                if (name.equals(pendingDelete)) {
                    SavedLists.delete(name);
                    pendingDelete = null;
                } else {
                    pendingDelete = name;
                }
                rebuildWidgets();
            }).bounds(savedX + savedWidth - 20, y, 20, 18)
              .tooltip(Tooltip.create(Component.literal(armed ? "Click again to delete \"" + name + "\"." : "Delete this saved list.")))
              .build());
        }
    }

    private void loadSaved(String name) {
        List<ShoppingListEntry> entries = SavedLists.get(name);
        if (entries == null) return;
        SandboxWidget.LoadResult result = widget.replaceList(entries);
        pendingDelete = null;
        saveName = name;
        Minecraft client = Minecraft.getInstance();
        if (result != null && client.player != null) {
            if (result.dropped() > 0) {
                client.player.sendOverlayMessage(Component.literal("Loaded \"" + name + "\" without its last "
                    + result.dropped() + " recipe(s): the list holds " + widget.getMaxRecipes() + " (Settings)")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            } else if (result.skipped() > 0) {
                client.player.sendOverlayMessage(Component.literal("Loaded \"" + name + "\": " + result.skipped()
                    + " recipe(s) already done, skipped")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
            }
        }
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        // Entries can disappear on their own (auto-remove); keep the rows in step with the list.
        List<String> names = widget.getShoppingList().stream().map(e -> e.recipe).toList();
        if (!names.equals(builtRows)) rebuildWidgets();
    }

    /** Top-level nodes the HUD shows: Total, then one tree per recipe. */
    private List<RecipeManager.RecipeNode> tops() {
        RecipeManager.RecipeNode root = widget.getDisplayRoot();
        return root == null || root.ingredients == null ? List.of() : root.ingredients;
    }

    private int forestHeight() {
        int height = 0;
        for (RecipeManager.RecipeNode top : tops()) height += getExpandedNodeHeight(top, SandboxWidget.LIST_KEY);
        return height;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderRecipeTab(context, mouseX, mouseY);
        // What Screen does, with a stratum per widget: the buttons don't overlap, so in one stratum each one is
        // tested against every element drawn before it.
        for (GuiEventListener child : children()) {
            if (!(child instanceof Renderable renderable)) continue;
            context.nextStratum();
            renderable.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    private void renderRecipeTab(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        MenuTabs.renderBackground(context, font, width, height, MenuTabs.Tab.SHOPPING_LIST);
        picker.render(context, mouseX, mouseY, "Click a recipe to add it", builtRowSet, null);
        renderListPanel(context);
        renderSavedLists(context);
        MenuTabs.sectionTitle(context, font, "Preview", treeViewX, treeViewY - 13);

        MenuTabs.panel(context, treeViewX, treeViewY, treeViewWidth, treeViewHeight);

        context.enableScissor(
            treeViewX,
            treeViewY,
            treeViewX + treeViewWidth,
            treeViewY + treeViewHeight
        );
        if (!tops().isEmpty()) {
            int treeTop = treeViewY + 10 - treeScrollOffset;
            int treeY = treeTop;
            for (RecipeManager.RecipeNode top : tops()) {
                treeY = renderRecipeTree(context, top, treeViewX + 10, treeY, 0, SandboxWidget.LIST_KEY, mouseX, mouseY);
            }
            // The draw pass already walked the tree; its end is the height.
            int totalHeight = treeY - treeTop;
            if (totalHeight > treeViewHeight) {
                if (treeScrollOffset > 0) {
                    String up = "▲";
                    context.text(font, up, treeViewX + treeViewWidth - 15 - font.width(up) / 2, treeViewY + 15, GOLD, false);
                }
                if (treeScrollOffset < totalHeight - treeViewHeight + 20) {
                    String down = "▼";
                    context.text(font, down, treeViewX + treeViewWidth - 15 - font.width(down) / 2, treeViewY + treeViewHeight - 15, GOLD, false);
                }
                int scrollbarWidth = 12;
                int scrollbarHeight = Math.max(40, treeViewHeight * treeViewHeight / totalHeight);
                int scrollbarY = treeViewY + (int)((treeViewHeight - scrollbarHeight) * ((float)treeScrollOffset / (totalHeight - treeViewHeight + 20)));
                context.fill(treeViewX + treeViewWidth - scrollbarWidth - 4, treeViewY, treeViewX + treeViewWidth - 4, treeViewY + treeViewHeight, MenuTabs.ROW_BG_ALT);
                context.fill(treeViewX + treeViewWidth - scrollbarWidth - 4, scrollbarY, treeViewX + treeViewWidth - 4, scrollbarY + scrollbarHeight, MenuTabs.BORDER);
            }
        } else if (!builtRows.isEmpty()) {
            context.text(font, "Working out the list...", treeViewX + 20, treeViewY + 20, MenuTabs.TEXT_DIM, false);
        } else {
            context.text(font, "Click recipes on the left", treeViewX + 10, treeViewY + 20, MenuTabs.TEXT_DIM, false);
        }
        context.disableScissor();
        List<String> prices = widget.getNpcPriceLines();
        if (!prices.isEmpty() && !builtRows.isEmpty()) {
            context.text(font, fitPlain("NPC price - " + String.join("   ", prices), treeViewWidth, 0xFFFF9D00),
                treeViewX, treeViewY + treeViewHeight + 4, 0xFFFFFFFF, false);
        }
    }

    private Component fitPlain(String text, int width, int color) {
        return RecipePicker.fitPlain(font, text, width, color);
    }

    private void renderListPanel(GuiGraphicsExtractor context) {
        // Names as of the last rebuild (tick() rebuilds when the list changes): no copy of the list per frame.
        List<String> list = builtRows;
        String header = "Shopping list (" + list.size() + "/" + widget.getMaxRecipes() + ")";
        MenuTabs.sectionTitle(context, font, header, treeViewX, MenuTabs.SECTION_Y);
        int rows = Math.min(list.size(), PANEL_MAX_ROWS);
        if (rows == 0) {
            context.text(font, "Empty - click a recipe on the left to add it", treeViewX + 4, PANEL_TOP + 5, MenuTabs.TEXT_DIM, false);
            return;
        }
        for (int i = 0; i < rows; i++) {
            int y = PANEL_TOP + i * PANEL_ROW;
            context.fill(treeViewX, y - 2, treeViewX + treeViewWidth, y + 20, i % 2 == 0 ? MenuTabs.ROW_BG : MenuTabs.ROW_BG_ALT);
            context.text(font, fitPlain(list.get(i), listNameWidth, MenuTabs.TEXT), treeViewX + 4, y + 5, 0xFFFFFFFF, false);
        }
        if (list.size() > rows) {
            context.text(font, "+" + (list.size() - rows) + " more", treeViewX + 4, PANEL_TOP + rows * PANEL_ROW, MenuTabs.TEXT_DIM, false);
        }
    }

    private void renderSavedLists(GuiGraphicsExtractor context) {
        if (savedWidth == 0) return;
        MenuTabs.sectionTitle(context, font, "Saved lists", savedX, MenuTabs.SECTION_Y);
        int count = SavedLists.names().size();
        if (count == 0) {
            context.text(font, fitPlain("Name the list and click Save", savedWidth, MenuTabs.TEXT_DIM), savedX, SAVED_TOP + 5, 0xFFFFFFFF, false);
            return;
        }
        if (savedScroll > 0) {
            context.text(font, "▲", savedX + savedWidth / 2 - font.width("▲") / 2, SAVED_TOP - 4, GOLD, false);
        }
        if (savedScroll + savedRowsShown < count) {
            context.text(font, "▼", savedX + savedWidth / 2 - font.width("▼") / 2, SAVED_TOP + savedRowsShown * PANEL_ROW, GOLD, false);
        }
    }

    /**
     * One row of the preview tree, drawn with the HUD's look (Settings > Appearance: colours, font, row boxes,
     * tree lines, bold names, forge times). Rows keep this screen's fixed 16 px height so clicks line up.
     */
    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level,
                                 String pathKey, int mouseX, int mouseY) {
        if (node == null) return y;
        // One stratum per row, as on the HUD (see SandboxWidget.renderRecipeTree).
        context.nextStratum();
        HudStyle style = HudStyle.get();
        int indent = level * RECIPE_LEVEL_INDENT;
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0 && !node.cooking;
        boolean showRemaining = widget.isShowRemaining();
        String nodeKey = SandboxWidget.makePathKey(pathKey, node.name);
        boolean isExpanded = widget.isNodeExpanded(nodeKey);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int nodeWidth = treeViewWidth - 20 - indent;
        int rowX = x + indent;

        if (style.showRowBoxes) {
            RoundedBox.fill(context, rowX, y, nodeWidth, 16, style.rowRadius, style.rowBackground);
            int border = SandboxWidget.progressBorderColor(node, showRemaining);
            for (int i = 0; i < style.rowBorderWidth; i++) {
                RoundedBox.outline(context, rowX + i, y + i, nodeWidth - 2 * i, 16 - 2 * i, Math.max(0, style.rowRadius - i), border);
            }
        }
        if (mouseX >= rowX && mouseX <= rowX + nodeWidth && mouseY >= y && mouseY <= y + 16) {
            context.fill(rowX, y, rowX + nodeWidth, y + 16, 0x22FFFFFF);
        }
        if (hasChildren) {
            context.text(font, style.text(isExpanded ? "▼" : "▶", style.itemText, false), rowX + 5, y + 4, 0xFFFFFFFF, style.textShadow);
        }

        int statusColor = SandboxWidget.progressColor(node, showRemaining);
        int nameColor = level == 0 ? style.rootText : hasEnough ? style.itemText : statusColor;
        boolean bold = level == 0 && style.boldRootNames;
        Component amount = style.text(SandboxWidget.amountText(node) + " ", statusColor, false);
        int amountX = rowX + (hasChildren ? 25 : 10);
        context.text(font, amount, amountX, y + 4, 0xFFFFFFFF, style.textShadow);

        // Long names are cut with "..." so the forge time (if any) always fits.
        String forge = SandboxWidget.forgeText(node, level);
        Component tag = forge.isEmpty() ? Component.empty() : style.text(forge, nameColor, false);
        int lockWidth = SandboxWidget.showsLock(node) ? SandboxWidget.LOCK_WIDTH : 0;
        int nameX = amountX + font.width(amount);
        int room = rowX + nodeWidth - 4 - nameX - font.width(tag) - lockWidth;
        Component name = SandboxWidget.fitName(font, style, node.name, nameColor, bold, room);
        context.text(font, name, nameX, y + 4, 0xFFFFFFFF, style.textShadow);
        context.text(font, tag, nameX + font.width(name), y + 4, 0xFFFFFFFF, style.textShadow);
        if (lockWidth > 0) SandboxWidget.drawLock(context, nameX + font.width(name) + font.width(tag), y + 4, nameColor);
        // Only inside the scrolled preview box, so a row scrolled out of view never shows a tooltip.
        boolean hovered = mouseX >= rowX && mouseX <= rowX + nodeWidth && mouseY >= y && mouseY <= y + 16
            && mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight;
        if (hovered) {
            // What unlocks the recipe, when it needs something; otherwise the item's description.
            List<Component> tooltip = lockWidth > 0 ? lockTooltip(node) : ItemDescriptions.tooltip(node.name);
            if (!tooltip.isEmpty()) context.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }

        y += 16;

        if (hasChildren && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                if (style.showTreeLines) {
                    int lineStartX = rowX + 6;
                    int childIndentX = x + indent + RECIPE_LEVEL_INDENT;
                    context.fill(lineStartX, y, lineStartX + 1, y + 8, style.treeLines);
                    context.fill(lineStartX, y + 8, childIndentX, y + 9, style.treeLines);
                }
                y = renderRecipeTree(context, child, x, y, level + 1, nodeKey, mouseX, mouseY);
            }
        }
        return y;
    }

    /** "Requires: HotM 6", then "Needs below:" and one line per requirement further down the tree. */
    private static List<Component> lockTooltip(RecipeManager.RecipeNode node) {
        List<Component> lines = new ArrayList<>();
        if (!node.ownLock.isEmpty()) lines.add(Component.literal("Requires: " + node.ownLock));
        if (!node.locksBelow.isEmpty()) {
            lines.add(Component.literal("Needs below:").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
            for (String lock : node.locksBelow) lines.add(Component.literal("  " + lock));
        }
        return lines;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (picker.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
        if (savedWidth > 0 && mouseX >= savedX && mouseX <= savedX + savedWidth && mouseY >= SAVED_TOP && verticalAmount != 0) {
            int max = Math.max(0, SavedLists.names().size() - savedRowsShown);
            int next = Math.max(0, Math.min(max, savedScroll + (verticalAmount < 0 ? 1 : -1)));
            if (next != savedScroll) {
                savedScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        if (mouseX >= treeViewX && mouseX <= treeViewX + treeViewWidth &&
            mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
            if (verticalAmount != 0 && !tops().isEmpty()) {
                int totalTreeHeight = forestHeight();
                int visibleHeight = treeViewHeight - 20;
                int scrollAmount = (int)(verticalAmount * -12);
                treeScrollOffset += scrollAmount;
                int maxScroll = Math.max(0, totalTreeHeight - visibleHeight);
                treeScrollOffset = Math.max(0, Math.min(treeScrollOffset, maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        if (picker.mouseClicked(ctx)) return true;
        String recipe = picker.nameAt(mouseX, mouseY);
        if (recipe != null) {
            SandboxWidget.AddResult result = widget.addToList(recipe, picker.amount());
            if (result == SandboxWidget.AddResult.FULL) {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal("Shopping list full ("
                        + widget.getShoppingList().size() + "/" + widget.getMaxRecipes() + ")")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                }
            } else {
                rebuildWidgets();
            }
            return true;
        }
        if (!tops().isEmpty() && mouseX >= treeViewX && mouseX <= treeViewX + treeViewWidth &&
            mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
            return handleTreeNodeClick(mouseX, mouseY);
        }
        return super.mouseClicked(ctx, doubleClick);
    }

    private boolean handleTreeNodeClick(double mouseX, double mouseY) {
        int x = treeViewX + 10;
        int y = treeViewY + 10 - treeScrollOffset;
        for (RecipeManager.RecipeNode top : tops()) {
            if (checkNodeClick(top, mouseX, mouseY, x, y, 0, SandboxWidget.LIST_KEY)) return true;
            y += getExpandedNodeHeight(top, SandboxWidget.LIST_KEY);
        }
        return false;
    }

    private boolean checkNodeClick(RecipeManager.RecipeNode node, double mouseX, double mouseY, int x, int y, int level, String pathKey) {
        if (node == null) return false;

        if (y + 16 < treeViewY || y > treeViewY + treeViewHeight) {
            y += 16;
            if (node.ingredients != null && !node.ingredients.isEmpty() &&
                widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
                for (RecipeManager.RecipeNode child : node.ingredients) {
                    boolean childResult = checkNodeClick(child, mouseX, mouseY, x, y, level + 1, SandboxWidget.makePathKey(pathKey, node.name));
                    if (childResult) return true;
                    y += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
                }
            }
            return false;
        }

        int indent = level * RECIPE_LEVEL_INDENT;
        int nodeHeight = 16;
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int nodeWidth = treeViewWidth - 20 - indent;

    if (mouseY >= y && mouseY <= y + nodeHeight &&
            mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
            if (mouseX >= x + indent && mouseX <= x + indent + nodeWidth) {
        String nodeKey = SandboxWidget.makePathKey(pathKey, node.name);
                if (hasChildren) {
                    widget.toggleNodeExpansion(nodeKey);
                    return true;
                }
                else {
                    Minecraft client = Minecraft.getInstance();
                    int available = ResourcesManager.getInstance().getResourceByName(node.name);
                    boolean hasEnough = available >= node.required;
                    Component message = Component.literal("You have " + available + "/" + node.required + " of " + node.name)
                        .setStyle(Style.EMPTY.withColor(hasEnough ? ChatFormatting.GREEN : ChatFormatting.RED));
                    if (client.player != null) {
                        client.player.sendOverlayMessage(message);
                    }
                    return true;
                }
            }
        }

        y += nodeHeight;

    if (hasChildren && widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
        if (checkNodeClick(child, mouseX, mouseY, x, y, level + 1, SandboxWidget.makePathKey(pathKey, node.name))) {
                    return true;
                }
        y += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
            }
        }
        return false;
    }

    private int getExpandedNodeHeight(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int height = 16;
        if (node.ingredients != null && !node.ingredients.isEmpty() &&
        widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
        for (RecipeManager.RecipeNode child : node.ingredients) {
        height += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
            }
        }
        return height;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
