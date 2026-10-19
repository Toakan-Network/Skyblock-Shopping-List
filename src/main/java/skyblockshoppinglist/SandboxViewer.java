package skyblockshoppinglist;

import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SandboxViewer extends Screen {

    private static final int PANEL_BG = MenuTabs.PANEL_BG;
    private static final int ITEM_BG = MenuTabs.ROW_BG;
    private static final int ITEM_BG_ALT = MenuTabs.ROW_BG_ALT;
    private static final int SELECTED_BG = MenuTabs.SELECTED_BG;
    private static final int BORDER_COLOR = MenuTabs.BORDER;
    private static final int GOLD = MenuTabs.GOLD;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = MenuTabs.TEXT;
    private static final int KEY_TAB = ShoppingListClient.inputCode("KEY_TAB");
    private static final int KEY_RETURN = ShoppingListClient.inputCode("KEY_RETURN");
    private static final int KEY_NUMPADENTER = ShoppingListClient.inputCode("KEY_NUMPADENTER");
    /** Top of the right-hand panel on the Recipes and Forge tabs. */
    private static final int PANEL_TOP = MenuTabs.CONTROLS_Y;
    private static final int SUCCESS_GREEN = 0xFF6EFF6E;
    private static final int ERROR_RED = 0xFFFF6B6B;

    public enum Mode {
        RESOURCES,
        RECIPES,
        FORGE
    }

    private final Mode mode;
    /** Your Resources: also list items you have none of. */
    private static boolean showAllResources = false;

    private final RecipeManager recipeManager = RecipeManager.getInstance();
    private final ResourcesManager resourcesManager = ResourcesManager.getInstance();

    private List<ResourcesManager.ResourceEntry> resources = new ArrayList<>();
    private List<ResourcesManager.ResourceEntry> filteredResources = new ArrayList<>();
    /** The filteredResources list the edit fields were last pruned for (it is replaced, never changed in place). */
    private List<ResourcesManager.ResourceEntry> prunedFor;
    private String resourceSearchTerm = "";

    /** Recipes and Forge tabs: search, filters and the recipe list on the left; its × box is the craft amount. */
    private RecipePicker picker;
    private String selectedRecipe = null;
    private RecipeManager.RecipeNode expandedRecipeTree = null;
    private Map<String, Integer> simpleRecipe = null;

    private ResourcesManager.RemainingResponse remainingResult = null;
    private int craftAmount = 1;
    private boolean craftable = false;
    private final List<String> messages = new ArrayList<>();

    private EditBox searchBox;
    private final Map<String, EditBox> resourceAmountFields = new HashMap<>();
    private String activeTextField = null;
    private int scrollOffset = 0;

    private final Map<String, Boolean> expandedNodes = new HashMap<>();

    private record ClickableElement(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableElement> clickableElements = new ArrayList<>();

    private int recipeCombinedScrollOffset = 0;
    private int recipeCombinedMaxScroll = 0;
    private int recipeCombinedAreaX = 0, recipeCombinedAreaY = 0, recipeCombinedAreaWidth = 0, recipeCombinedAreaHeight = 0;

    private int forgeCombinedScrollOffset = 0;
    private int forgeCombinedMaxScroll = 0;
    private int forgeCombinedAreaX = 0, forgeCombinedAreaY = 0, forgeCombinedAreaWidth = 0, forgeCombinedAreaHeight = 0;


    public SandboxViewer(Mode mode) {
        super(Component.literal(ShoppingList.NAME));
        this.mode = mode;
        // Open on the first shopping-list recipe, if there is one.
        List<ShoppingListEntry> list = SandboxWidget.getInstance().getShoppingList();
        if (!list.isEmpty()) {
            selectedRecipe = list.get(0).recipe;
            craftAmount = list.get(0).amount;
        }
    }

    @Override
    protected void init() {
        this.clearWidgets();

        MenuTabs.buttons(tab(), this.width).forEach(this::addRenderableWidget);

        switch (mode) {
            case RESOURCES -> initModifyResources();
            case RECIPES, FORGE -> initRecipeTabs();
        }
    }

    private MenuTabs.Tab tab() {
        return switch (mode) {
            case RESOURCES -> MenuTabs.Tab.RESOURCES;
            case RECIPES -> MenuTabs.Tab.RECIPES;
            case FORGE -> MenuTabs.Tab.FORGE;
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {}


    /**
     * Recipes and Forge tabs: the recipe picker on the left, and along the bottom the HUD switch and Add to list, where
     * the List tab has its HUD switch and Clear list.
     */
    private void initRecipeTabs() {
        if (picker == null) picker = new RecipePicker(font, craftAmount, this::setCraftAmount);
        picker.init(this::addRenderableWidget, width, height);
        addHudButton();
        this.addRenderableWidget(Button.builder(Component.literal("Add to list"), button -> addSelectedToList())
            .bounds(width / 2 - 60, height - MenuTabs.BOTTOM_BAR, 120, 20).build());
        loadRecipes();
        if (mode == Mode.FORGE && selectedRecipe != null && remainingResult == null) {
            checkRecipeRequirements();
        }
    }

    /** The HUD on/off switch, bottom left in every tab. */
    private void addHudButton() {
        SandboxWidget widget = SandboxWidget.getInstance();
        this.addRenderableWidget(Button.builder(hudLabel(), button -> {
            widget.setEnabled(!widget.isEnabled());
            button.setMessage(hudLabel());
        }).bounds(MenuTabs.LEFT_X, height - MenuTabs.BOTTOM_BAR, 150, 20).build());
    }

    private static Component hudLabel() {
        return Component.literal("HUD: " + (SandboxWidget.getInstance().isEnabled() ? "ON" : "OFF"));
    }

    private void addSelectedToList() {
        Minecraft client = Minecraft.getInstance();
        if (selectedRecipe == null) {
            if (client.player != null) client.player.sendOverlayMessage(Component.literal("Pick a recipe first"));
            return;
        }
        SandboxWidget widget = SandboxWidget.getInstance();
        SandboxWidget.AddResult result = widget.addToList(selectedRecipe, craftAmount);
        String message = switch (result) {
            case ADDED -> "Added " + craftAmount + "× " + selectedRecipe + " to the shopping list";
            case INCREASED -> "Added " + craftAmount + " more " + selectedRecipe;
            case FULL -> "Shopping list full (" + widget.getShoppingList().size() + "/" + widget.getMaxRecipes() + ")";
            case WAITING -> SandboxWidget.WAITING_MESSAGE;
        };
        if (client.player != null) client.player.sendOverlayMessage(Component.literal(message));
    }

    private void initModifyResources() {
        resourceAmountFields.clear();
        activeTextField = null;

        searchBox = new EditBox(this.font, MenuTabs.LEFT_X, MenuTabs.CONTROLS_Y, 210, 20, Component.literal("Search Resources"));
        searchBox.setHint(Component.literal("Search resources..."));
        searchBox.setValue(resourceSearchTerm);
        searchBox.setResponder(this::onResourceSearchChanged);
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(Button.builder(showAllLabel(), button -> {
            showAllResources = !showAllResources;
            loadResources();
            button.setMessage(showAllLabel());
        }).bounds(MenuTabs.LEFT_X + 216, MenuTabs.CONTROLS_Y, 110, 20)
          .tooltip(Tooltip.create(Component.literal("OFF: only items you have. ON: every tracked item, so you can set one you have none of.")))
          .build());
        addHudButton();

        loadResources();
    }


    private static Component showAllLabel() {
        return Component.literal("Show all: " + (showAllResources ? "ON" : "OFF"));
    }

    private void loadResources() {
        boolean searching = resourceSearchTerm != null && !resourceSearchTerm.isEmpty();
        resources = showAllResources || searching
            ? resourcesManager.getAllResourceEntriesIncludingZero()
            : resourcesManager.getAllResourceEntries();
        filterResources();
    }

    private void loadRecipes() {
        if (selectedRecipe != null) {
            expandedRecipeTree = recipeManager.expandRecipe(selectedRecipe, craftAmount);
            simpleRecipe = recipeManager.getSimpleRecipe(selectedRecipe, craftAmount);
        }
    }

    private void filterResources() {
        String term = resourceSearchTerm.toLowerCase(Locale.ROOT);
        filteredResources = resources.stream()
            .filter(resource -> term.isEmpty() || resource.name.toLowerCase(Locale.ROOT).contains(term))
            .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
            .collect(Collectors.toList());
        int maxVisible = getResourceMaxVisibleItems();
        int maxOffset = Math.max(0, filteredResources.size() - maxVisible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    private void onResourceSearchChanged(String text) {
        resourceSearchTerm = text;

        scrollOffset = 0;
        loadResources();
    }

    private void setCraftAmount(int amount) {
        craftAmount = Math.max(1, amount);
        if (selectedRecipe != null) {
            expandedRecipeTree = recipeManager.expandRecipe(selectedRecipe, craftAmount);
            simpleRecipe = recipeManager.getSimpleRecipe(selectedRecipe, craftAmount);
            if (mode == Mode.FORGE) {
                checkRecipeRequirements();
            }
        }
    }

    private void checkRecipeRequirements() {
        if (selectedRecipe == null) return;

        remainingResult = resourcesManager.getRemainingIngredients(selectedRecipe, craftAmount);

        messages.clear();
        if (remainingResult.messages != null && !remainingResult.messages.isEmpty()) {
            for (Map.Entry<String, Integer> entry : remainingResult.messages.entrySet()) {
                String message = "You need to craft x" + entry.getValue() + " " + entry.getKey();
                messages.add(message);
            }
        }

        craftable = remainingResult.full_recipe != null
            && remainingResult.full_recipe.ingredients.stream().allMatch(child -> child.amount <= 0);

        if (messages.isEmpty() && !craftable) {
            String message = "Missing resources to craft " + selectedRecipe;
            messages.add(message);
        }
    }

    private void selectRecipe(String name) {
        selectedRecipe = name;
        expandedRecipeTree = recipeManager.expandRecipe(name, craftAmount);
        simpleRecipe = recipeManager.getSimpleRecipe(name, craftAmount);
        messages.clear();

        this.init();
        if (mode == Mode.FORGE) {
            checkRecipeRequirements();
        }
    }

    private void toggleNodeExpanded(String nodePath) {
        expandedNodes.put(nodePath, !expandedNodes.getOrDefault(nodePath, false));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();

        if (picker != null && mode != Mode.RESOURCES) {
            if (picker.mouseClicked(ctx)) return true;
            String recipe = picker.nameAt(mouseX, mouseY);
            if (recipe != null) {
                selectRecipe(recipe);
                return true;
            }
        }

        if (mode == Mode.RESOURCES) {
            String previousActiveField = activeTextField;
            activeTextField = null;

            boolean textFieldClicked = false;
            for (Map.Entry<String, EditBox> entry : resourceAmountFields.entrySet()) {
                EditBox field = entry.getValue();
                if (field.isMouseOver(mouseX, mouseY)) {
                    field.setFocused(true);
                    textFieldClicked = true;
                    activeTextField = entry.getKey();

                    if (!entry.getKey().equals(previousActiveField)) {
                        field.setCursorPosition(0);
                        field.setHighlightPos(field.getValue().length());
                    }
                } else {
                    field.setFocused(false);
                }
            }

            if (textFieldClicked) {
                return true;
            }
        }

        for (ClickableElement element : clickableElements) {
            if (mouseX >= element.x && mouseX < element.x + element.width &&
                mouseY >= element.y && mouseY < element.y + element.height) {
                element.action.run();
                return true;
            }
        }

        // (Screen's new event system handles child widget clicks automatically)
        return super.mouseClicked(ctx, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (mode == Mode.RESOURCES && activeTextField != null) {
            EditBox field = resourceAmountFields.get(activeTextField);
            if (field != null && field.isFocused()) {
                if (keyCode == KEY_TAB) { // move between fields
                    moveToNextTextField(event.hasShiftDown());
                    return true;
                }
                if (keyCode == KEY_RETURN || keyCode == KEY_NUMPADENTER) { // confirm and advance
                    field.setFocused(false);
                    moveToNextTextField(false);
                    return true;
                }
                if (field.keyPressed(event)) {
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    private void moveToNextTextField(boolean reverse) {
        if (filteredResources.isEmpty() || activeTextField == null) return;

        int maxVisibleItems = getResourceMaxVisibleItems();
        int startIndex = Math.min(scrollOffset, Math.max(0, filteredResources.size() - maxVisibleItems));
        int endIndex = Math.min(startIndex + maxVisibleItems, filteredResources.size());

        List<String> visibleResources = new ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            visibleResources.add(filteredResources.get(i).name);
        }

        if (visibleResources.isEmpty()) return;

        int currentIndex = visibleResources.indexOf(activeTextField);
        if (currentIndex == -1) {
            currentIndex = 0;
        } else {
            if (reverse) {
                currentIndex = (currentIndex - 1 + visibleResources.size()) % visibleResources.size();
            } else {
                currentIndex = (currentIndex + 1) % visibleResources.size();
            }
        }

        if (resourceAmountFields.containsKey(activeTextField)) {
            resourceAmountFields.get(activeTextField).setFocused(false);
        }

        String newActiveField = visibleResources.get(currentIndex);
        activeTextField = newActiveField;

        if (resourceAmountFields.containsKey(newActiveField)) {
            EditBox field = resourceAmountFields.get(newActiveField);
            field.setFocused(true);
            field.setCursorPosition(0);
            field.setHighlightPos(field.getValue().length());
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (mode == Mode.RESOURCES && activeTextField != null) {
            EditBox field = resourceAmountFields.get(activeTextField);
            if (field != null && field.isFocused() && field.charTyped(event)) {
                return true;
            }
        }
        return super.charTyped(event);
    }

    /** Rows of the Resources table between its header (under the controls row) and the bottom buttons. */
    private int getResourceMaxVisibleItems() {
        int listHeight = Math.max(0, panelBottom() - (MenuTabs.FILTER_Y + 24));
        return Math.max(1, listHeight / 30);
    }

    /** Bottom of the content panels, just above the bottom buttons. */
    private int panelBottom() {
        return this.height - MenuTabs.BOTTOM_BAR - 8;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mode == Mode.RECIPES && recipeCombinedAreaWidth > 0 && recipeCombinedAreaHeight > 0) {
            if (mouseX >= recipeCombinedAreaX && mouseX <= recipeCombinedAreaX + recipeCombinedAreaWidth &&
                mouseY >= recipeCombinedAreaY && mouseY <= recipeCombinedAreaY + recipeCombinedAreaHeight) {
                recipeCombinedScrollOffset -= (int)(verticalAmount * 24);
                recipeCombinedScrollOffset = Math.max(0, Math.min(recipeCombinedScrollOffset, recipeCombinedMaxScroll));
                return true;
            }
        }

        if (mode == Mode.FORGE && forgeCombinedAreaWidth > 0 && forgeCombinedAreaHeight > 0) {
            if (mouseX >= forgeCombinedAreaX && mouseX <= forgeCombinedAreaX + forgeCombinedAreaWidth &&
                mouseY >= forgeCombinedAreaY && mouseY <= forgeCombinedAreaY + forgeCombinedAreaHeight) {
                forgeCombinedScrollOffset -= (int)(verticalAmount * 24);
                forgeCombinedScrollOffset = Math.max(0, Math.min(forgeCombinedScrollOffset, forgeCombinedMaxScroll));
                return true;
            }
        }

        if (picker != null && mode != Mode.RESOURCES && picker.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
        int maxVisibleItems = getResourceMaxVisibleItems();
        int maxItems = filteredResources.size();

        if (mode == Mode.RESOURCES && maxItems > maxVisibleItems) {
            scrollOffset -= (int) verticalAmount;
            scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, maxItems - maxVisibleItems)));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        try {
            MenuTabs.renderBackground(context, font, this.width, this.height, tab());
            clickableElements.clear();

            switch (mode) {
                case RESOURCES -> {
                    MenuTabs.sectionTitle(context, font, "Your resources", MenuTabs.LEFT_X, MenuTabs.SECTION_Y);
                    int contentWidth = this.width - 2 * MenuTabs.LEFT_X;
                    renderModifyResources(context, MenuTabs.LEFT_X, MenuTabs.FILTER_Y, contentWidth, panelBottom() - MenuTabs.FILTER_Y);
                }
                case RECIPES, FORGE -> renderRecipeTabs(context, mouseX, mouseY);
            }

            super.extractRenderState(context, mouseX, mouseY, delta);
        } catch (Exception e) {
            ShoppingList.LOGGER.error("Error in render method", e);
        }
    }



    private boolean isMouseOver(int x, int y, int width, int height) {
        double mouseX = this.minecraft.mouseHandler.xpos() * (double)this.minecraft.getWindow().getGuiScaledWidth() / (double)this.minecraft.getWindow().getScreenWidth();
        double mouseY = this.minecraft.mouseHandler.ypos() * (double)this.minecraft.getWindow().getGuiScaledHeight() / (double)this.minecraft.getWindow().getScreenHeight();
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Recipes and Forge tabs: the picker on the left, the selected recipe in a panel on the right. */
    private void renderRecipeTabs(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        picker.render(context, mouseX, mouseY, "Pick a recipe", Set.of(), selectedRecipe);
        int x = picker.rightColumnX();
        int width = Math.max(120, this.width - x - MenuTabs.LEFT_X);
        int height = panelBottom() - PANEL_TOP;
        MenuTabs.sectionTitle(context, font, selectedRecipe != null ? selectedRecipe : (mode == Mode.FORGE ? "Forge" : "Recipe"),
            x, MenuTabs.SECTION_Y);
        boolean ready = selectedRecipe != null && (mode == Mode.FORGE ? remainingResult != null : expandedRecipeTree != null);
        if (!ready) {
            MenuTabs.panel(context, x, PANEL_TOP, width, height);
            drawCenteredText(context, mode == Mode.FORGE ? "Select a recipe to forge" : "Select a recipe from the list",
                x + width / 2, PANEL_TOP + height / 2, TEXT_SECONDARY);
        } else if (mode == Mode.FORGE) {
            renderForgeDetails(context, x, PANEL_TOP, width, height);
        } else {
            renderRecipeDetails(context, x, PANEL_TOP, width, height);
        }
    }

    private void renderRecipeDetails(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        recipeCombinedAreaX = x;
        recipeCombinedAreaY = y;
        recipeCombinedAreaWidth = width;
        recipeCombinedAreaHeight = height;

        int[] scroll = renderMaterialsAndTree(context, x + 6, width - 12, recipeCombinedAreaX, recipeCombinedAreaY,
            recipeCombinedAreaWidth, recipeCombinedAreaHeight, recipeCombinedScrollOffset, expandedRecipeTree,
            "Crafting Tree (Click to expand)");
        recipeCombinedMaxScroll = scroll[0];
        recipeCombinedScrollOffset = scroll[1];
    }

    private void renderForgeDetails(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int rightColumnY = y;

        context.fill(x, rightColumnY, x + width, rightColumnY + 60, ITEM_BG);
        context.outline(x, rightColumnY, width, 60, BORDER_COLOR);
        context.text(font, "Crafting Amount: ", x + 10, rightColumnY + 10, WHITE, false);
        context.text(font, craftAmount + "", x + 120, rightColumnY + 10, GOLD, false);
        String statusIcon = craftable ? "✓" : "✗";
        String craftableText = craftable ? "Can be crafted!" : "Missing ingredients";
        int craftableColor = craftable ? SUCCESS_GREEN : ERROR_RED;
        context.text(font, statusIcon, x + 10, rightColumnY + 30, craftableColor, false);
        context.text(font, craftableText, x + 30, rightColumnY + 30, craftableColor, false);
        rightColumnY += 70;

        if (!messages.isEmpty()) {
            context.fill(x, rightColumnY, x + width, rightColumnY + 30, ITEM_BG_ALT);
            context.text(font, "Crafting Messages", x + 10, rightColumnY + 10, GOLD, false);
            rightColumnY += 35;
            int msgBoxHeight = Math.min(messages.size() * 20 + 10, 100);
            context.fill(x, rightColumnY, x + width, rightColumnY + msgBoxHeight, PANEL_BG);
            context.outline(x, rightColumnY, width, msgBoxHeight, BORDER_COLOR);
            int msgY = rightColumnY + 8;
            for (int i = 0; i < messages.size() && msgY < rightColumnY + msgBoxHeight - 15; i++) {
                String msg = "• " + messages.get(i);
                context.text(font, msg, x + 12, msgY, TEXT_SECONDARY, false);
                msgY += 20;
            }
            rightColumnY += msgBoxHeight + 15;
        }

        forgeCombinedAreaX = x;
        forgeCombinedAreaY = rightColumnY;
        forgeCombinedAreaWidth = width;
        forgeCombinedAreaHeight = y + height - rightColumnY;

        int[] scroll = renderMaterialsAndTree(context, x + 6, width - 12, forgeCombinedAreaX, forgeCombinedAreaY,
            forgeCombinedAreaWidth, forgeCombinedAreaHeight, forgeCombinedScrollOffset,
            remainingResult != null ? remainingResult.full_recipe : null, "Required Recipe Tree (Click to Expand)");
        forgeCombinedMaxScroll = scroll[0];
        forgeCombinedScrollOffset = scroll[1];
    }

    /**
     * The scrollable area of the Recipes and Forge tabs: "Required Materials" cards, then the recipe tree, with a
     * scrollbar. Returns {max scroll, scroll offset clamped to it}.
     */
    private int[] renderMaterialsAndTree(GuiGraphicsExtractor context, int x, int width, int areaX, int areaY,
                                         int areaWidth, int areaHeight, int scrollOffset, Object tree, String treeTitle) {
        context.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, PANEL_BG);
        context.outline(areaX, areaY, areaWidth, areaHeight, BORDER_COLOR);

        int contentY = areaY + 10;
        int headerH = 20;
        int cardWidth = 180;
        int cardSpacing = 10;
        int cardsPerRow = Math.max(1, (width - 20) / cardWidth);
        boolean noMaterials = simpleRecipe == null || simpleRecipe.isEmpty();
        int materialRows = noMaterials ? 0 : (simpleRecipe.size() + cardsPerRow - 1) / cardsPerRow;
        int contentHeightMaterials = headerH + 6 + (noMaterials ? 24 : materialRows * 34 + 4);
        int treeTop = contentY + contentHeightMaterials + 10 + headerH + 6;
        int contentHeightTree = (renderRecipeTree(null, tree, "", x + 15, treeTop + 4) - treeTop) + 4;

        int totalContentHeight = contentHeightMaterials + 10 + headerH + 6 + contentHeightTree;
        int maxScroll = Math.max(0, totalContentHeight - areaHeight + 10);
        int offset = Math.max(0, Math.min(scrollOffset, maxScroll));

        context.enableScissor(areaX, areaY, areaX + areaWidth, areaY + areaHeight);
        int drawY = contentY - offset;

        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, "Required Materials", x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        if (!noMaterials) {
            int materialIndex = 0;
            for (Map.Entry<String, Integer> entry : simpleRecipe.entrySet()) {
                int itemX = x + (materialIndex % cardsPerRow) * (cardWidth + cardSpacing);
                int itemY = drawY + (materialIndex / cardsPerRow) * 34;
                if (itemY + 30 >= areaY && itemY <= areaY + areaHeight) {
                    context.fill(itemX, itemY, itemX + cardWidth, itemY + 28, ITEM_BG);
                    context.outline(itemX, itemY, cardWidth, 28, BORDER_COLOR);
                    context.text(font, entry.getKey(), itemX + 8, itemY + 10, WHITE, false);
                    String qtyText = entry.getValue() + "×";
                    context.text(font, qtyText, itemX + cardWidth - font.width(qtyText) - 8, itemY + 10, GOLD, false);
                }
                materialIndex++;
            }
            drawY += materialRows * 34 + 4;
        } else {
            context.text(font, "No materials required", x + 10, drawY + 5, TEXT_SECONDARY, false);
            drawY += 24;
        }

        drawY += 10;
        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, treeTitle, x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        renderRecipeTree(context, tree, "", x + 15, drawY);

        context.disableScissor();

        if (maxScroll > 0) {
            int sbWidth = 6;
            int sbX = areaX + areaWidth - sbWidth - 4;
            int sbY = areaY + 2;
            int sbHeight = areaHeight - 4;
            context.fill(sbX, sbY, sbX + sbWidth, sbY + sbHeight, ITEM_BG_ALT);
            int thumbHeight = Math.max(10, (int) (sbHeight * (areaHeight / (float) (totalContentHeight + 1))));
            int thumbY = sbY + (int) ((offset / (float) maxScroll) * (sbHeight - thumbHeight));
            context.fill(sbX, thumbY, sbX + sbWidth, thumbY + thumbHeight, BORDER_COLOR);
        }
        return new int[] {maxScroll, offset};
    }

    private int renderRecipeTree(GuiGraphicsExtractor context, Object nodeObj, String path, int x, int y) {
        if (nodeObj == null) return y;

        int lineHeight = 24;
        String name;
        int amount;
        int required;
        List<?> ingredients;

        if (nodeObj instanceof RecipeManager.RecipeNode node) {
            name = node.name;
            amount = node.amount;
            required = node.required;
            ingredients = node.ingredients;
        } else {
            return y;
        }

        String fullPath = path + name;
        boolean isExpanded = expandedNodes.getOrDefault(fullPath, false);
        boolean hasIngredients = ingredients != null && !ingredients.isEmpty();

        int textColor = WHITE;
        // The Forge tab shows a shopping-list tree: amount is how many are still missing, so any is red.
        if (mode == Mode.FORGE && required > 0) {
            textColor = amount > 0 ? ERROR_RED : SUCCESS_GREEN;
        }

        int nodeWidth = Math.min(300, Math.max(100, font.width(name) + font.width(amount + "×") + 40));
        if (context != null) {
            context.fill(x - 5, y, x + nodeWidth, y + lineHeight, ITEM_BG);
            context.outline(x - 5, y, nodeWidth + 5, lineHeight, BORDER_COLOR);
            if (isMouseOver(x - 5, y, nodeWidth, lineHeight)) {
                context.fill(x - 5, y, x + nodeWidth, y + lineHeight, SELECTED_BG);
                // Only inside the scrolled area, so a row scrolled out of view never shows a tooltip.
                if (lastMouseY >= treeAreaTop() && lastMouseY <= treeAreaBottom()) {
                    List<Component> tooltip = ItemDescriptions.tooltip(name);
                    if (!tooltip.isEmpty()) context.setComponentTooltipForNextFrame(font, tooltip, lastMouseX, lastMouseY);
                }
            }
            if (hasIngredients) {
                clickableElements.add(new ClickableElement(x - 5, y, nodeWidth, lineHeight, () -> toggleNodeExpanded(fullPath)));
            }
            context.text(font, amount + "×", x + 15, y + (lineHeight - font.lineHeight) / 2, GOLD, false);
            context.text(font, name, x + 15 + font.width(amount + "×") + 5, y + (lineHeight - font.lineHeight) / 2, textColor, false);
        }
        int endY = y + lineHeight;
        if (isExpanded && hasIngredients) {
            int childY = y + lineHeight + 2;
            int indent = 24;
            for (Object child : ingredients) {
                int nextY = renderRecipeTree(context, child, fullPath + ".", x + indent, childY);
                childY = nextY + 4;
            }
            endY = childY - 4;
        }
        return endY;
    }

    /** Top and bottom of the scrolled materials-and-tree area of the current tab. */
    private int treeAreaTop() {
        return mode == Mode.FORGE ? forgeCombinedAreaY : recipeCombinedAreaY;
    }

    private int treeAreaBottom() {
        return mode == Mode.FORGE ? forgeCombinedAreaY + forgeCombinedAreaHeight : recipeCombinedAreaY + recipeCombinedAreaHeight;
    }

    private void drawCenteredText(GuiGraphicsExtractor context, String text, int centerX, int y, int color) {
        context.text(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderModifyResources(GuiGraphicsExtractor context, int contentX, int contentY, int contentWidth, int contentHeight) {
        int lineHeight = 30;

        int leftPanelWidth = contentWidth;

        // Drop edit fields for items no longer listed; only when the list was replaced, not every frame.
        if (filteredResources != prunedFor) {
            prunedFor = filteredResources;
            Set<String> currentResourceKeys = new HashSet<>();
            for (ResourcesManager.ResourceEntry resource : filteredResources) {
                currentResourceKeys.add(resource.name);
            }
            resourceAmountFields.entrySet().removeIf(entry -> !currentResourceKeys.contains(entry.getKey()));
        }

        int gridStartY = contentY;
        int totalItems = filteredResources.size();
        int maxVisibleItems = getResourceMaxVisibleItems();
        int startIndex = Math.min(scrollOffset, Math.max(0, totalItems - maxVisibleItems));

        int headerHeight = 24;
        context.fill(contentX, gridStartY, contentX + leftPanelWidth, gridStartY + headerHeight, ITEM_BG_ALT);
        context.outline(contentX, gridStartY, leftPanelWidth, headerHeight, BORDER_COLOR);

        context.text(font, "Resource Name", contentX + 12, gridStartY + 8, GOLD, false);
        context.text(font, "Amount", contentX + leftPanelWidth - 120, gridStartY + 8, GOLD, false);
        context.text(font, "Actions", contentX + leftPanelWidth - 60, gridStartY + 8, GOLD, false);

        int listStartY = gridStartY + headerHeight;
        context.fill(contentX, listStartY, contentX + leftPanelWidth, contentY + contentHeight, PANEL_BG);
        context.outline(contentX, gridStartY, leftPanelWidth, contentHeight, BORDER_COLOR);

        if (totalItems == 0) {
            drawCenteredText(context, "No resources found", contentX + leftPanelWidth / 2, listStartY + 30, TEXT_SECONDARY);
            return;
        }


        for (int i = startIndex; i < Math.min(startIndex + maxVisibleItems, totalItems); i++) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(i);
            int row = i - startIndex;
            int rowY = listStartY + row * lineHeight;

            int x = contentX + 5;
            int y = rowY + 5;

            int bgColor = (i % 2 == 0) ? ITEM_BG : ITEM_BG_ALT;
            context.fill(x, y, x + leftPanelWidth - 10, y + lineHeight - 10, bgColor);

            context.text(font, resource.name, x + 5, y + 7, WHITE, false);

            final String resourceKey = resource.name;
            int amountFieldWidth = 60;
            int amountFieldX = x + leftPanelWidth - 140;
            int amountFieldY = y + 2;

            EditBox amountField = resourceAmountFields.get(resourceKey);
            if (amountField == null) {
                final EditBox newField = new EditBox(font, amountFieldX, amountFieldY, amountFieldWidth, 16, Component.literal(""));
                newField.setValue(String.valueOf(resource.amount));
                newField.setMaxLength(10);

                newField.setResponder(text -> {
                    try {
                        int newAmount = text.isEmpty() ? 0 : Integer.parseInt(text);
                        if (newAmount >= 0) {
                            ResourcesManager.ResourceEntry resourceEntry = filteredResources.stream()
                                .filter(entry -> entry.name.equals(resourceKey))
                                .findFirst()
                                .orElse(null);

                            if (resourceEntry != null) {
                                resourceEntry.amount = newAmount;
                                updateSelectedResource(resourceEntry);
                            }
                        }
                    } catch (NumberFormatException e) {
                        ResourcesManager.ResourceEntry resourceEntry = filteredResources.stream()
                            .filter(entry -> entry.name.equals(resourceKey))
                            .findFirst()
                            .orElse(null);

                        if (resourceEntry != null && resourceAmountFields.containsKey(resourceKey)) {
                            EditBox fieldToUpdate = resourceAmountFields.get(resourceKey);
                            fieldToUpdate.setValue(String.valueOf(resourceEntry.amount));
                        }
                    }
                });

                resourceAmountFields.put(resourceKey, newField);
                amountField = newField;


                amountField.setBordered(true);
            } else {
                amountField.setX(amountFieldX);
                amountField.setY(amountFieldY);

                String currentText = amountField.getValue();
                int currentAmount;
                try {
                    currentAmount = currentText.isEmpty() ? 0 : Integer.parseInt(currentText);
                } catch (NumberFormatException e) {
                    currentAmount = -1;
                }

                if (currentAmount != resource.amount && !amountField.isFocused()) {
                    amountField.setValue(String.valueOf(resource.amount));
                }
            }

            int fieldBgColor = amountField.isFocused() ? 0xFF404040 : 0xFF333333;
            int fieldBorderColor = amountField.isFocused() ? WHITE : BORDER_COLOR;

            context.fill(amountFieldX - 1, amountFieldY - 1, amountFieldX + amountFieldWidth + 1, amountFieldY + 18, fieldBorderColor);
            context.fill(amountFieldX, amountFieldY, amountFieldX + amountFieldWidth, amountFieldY + 17, fieldBgColor);

            amountField.extractRenderState(context, lastMouseX, lastMouseY, 0);

            int buttonSize = 16;
            int buttonY = y + (lineHeight - 10 - buttonSize) / 2;

            int minusX = x + leftPanelWidth - 80;
            context.fill(minusX, buttonY, minusX + buttonSize, buttonY + buttonSize, 0xFF444444);
            context.outline(minusX, buttonY, buttonSize, buttonSize, BORDER_COLOR);
            context.text(font, "-", minusX + (buttonSize - font.width("-")) / 2,
                             buttonY + (buttonSize - font.lineHeight) / 2, WHITE, false);

            int plusX = x + leftPanelWidth - 40;
            context.fill(plusX, buttonY, plusX + buttonSize, buttonY + buttonSize, 0xFF444444);
            context.outline(plusX, buttonY, buttonSize, buttonSize, BORDER_COLOR);
            context.text(font, "+", plusX + (buttonSize - font.width("+")) / 2,
                             buttonY + (buttonSize - font.lineHeight) / 2, WHITE, false);

            final int resourceIndex = i;
            clickableElements.add(new ClickableElement(minusX, buttonY, buttonSize, buttonSize,
                                                      () -> decrementResource(resourceIndex)));
            clickableElements.add(new ClickableElement(plusX, buttonY, buttonSize, buttonSize,
                                                      () -> incrementResource(resourceIndex)));
        }

        if (totalItems > maxVisibleItems) {
            int scrollHeight = contentHeight - (listStartY - contentY);
            int scrollThumbHeight = Math.max(32, scrollHeight * maxVisibleItems / totalItems);
            int scrollThumbY = listStartY + (scrollOffset * (scrollHeight - scrollThumbHeight)) / (totalItems - maxVisibleItems);

            context.fill(contentX + leftPanelWidth - 8, listStartY, contentX + leftPanelWidth - 4, contentY + contentHeight, 0xFF333333);
            context.fill(contentX + leftPanelWidth - 8, scrollThumbY, contentX + leftPanelWidth - 4, scrollThumbY + scrollThumbHeight, 0xFF666666);
        }
    }

    private void incrementResource(int index) {
        if (index >= 0 && index < filteredResources.size()) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(index);
            resource.amount++;
            updateSelectedResource(resource);
        }
    }

    private void decrementResource(int index) {
        if (index >= 0 && index < filteredResources.size()) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(index);
            if (resource.amount > 0) {
                resource.amount--;
                updateSelectedResource(resource);
            }
        }
    }

    /** Edits apply straight away; there is no separate save step. */
    private void updateSelectedResource(ResourcesManager.ResourceEntry resource) {
        resourcesManager.setResourceAmount(resource.name, resource.amount);
    }

}
