package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SandboxWidget {
    public static final Identifier SANDBOX_WIDGET_LAYER = Identifier.fromNamespaceAndPath(ShoppingList.MOD_ID, "sandbox_widget");
    /** Expansion-key root for the top-level nodes (Total and each recipe). */
    public static final String LIST_KEY = "list";
    public static final int MAX_RECIPES_LIMIT = 10;
    private static final java.lang.reflect.Type SHOPPING_LIST_TYPE = new TypeToken<List<ShoppingListEntry>>() {}.getType();
    private volatile boolean enabled = false;
    /** Entries are never mutated in place; changes replace the entry so the update thread sees whole values. */
    private final List<ShoppingListEntry> shoppingList = new CopyOnWriteArrayList<>();
    /** Saved list last loaded or saved (List tab), so Save writes back to it; this session only, "" for none. */
    private volatile String currentSavedList = "";
    /**
     * Written by the update thread, read by the render thread; always replaced, never mutated. A synthetic
     * root whose children (Total, then one tree per recipe) are what the HUD shows.
     */
    private volatile RecipeManager.RecipeNode recipeTree = null;
    private int widgetX = 10;
    private int widgetY = 40;
    private int widgetWidth = 250;
    private int widgetHeight = 300;
    /** Main panel scale in percent, on top of the HUD scale from Settings. */
    private int widgetScale = 100;
    /** GUI-scaled screen size when the main panel was placed (0 = not recorded yet). */
    private int widgetRefWidth = 0;
    private int widgetRefHeight = 0;
    /** Craftable / Forging / Total panels, used when Settings puts them in their own panel. */
    private PanelRect craftablePanel = new PanelRect(270, 40, 180, 150);
    private PanelRect forgingPanel = new PanelRect(270, 200, 180, 120);
    private PanelRect totalPanel = new PanelRect(270, 330, 180, 150);
    private PanelRect mobDropsPanel = new PanelRect(270, 490, 180, 100);
    private volatile Map<String, Boolean> expandedNodes = new ConcurrentHashMap<>();
    /**
     * Craftable lines ("3× Refined Diamond"), most first, ready to draw; replaced whole (never cleared and refilled)
     * so the HUD never sees an empty list mid-update.
     */
    private volatile List<Line> messages = List.of();
    /** "Forging -" lines for both Forging section settings, built together so they never disagree. */
    private volatile ForgingLines forgingLines = ForgingLines.EMPTY;
    /** "Mob drops" lines: kills so far and the kill estimates for missing items that drop from mobs. */
    private volatile List<Line> mobDropLines = List.of();
    /** Update thread only: kill count, drop table and settings the mob drop lines were built for. */
    private String mobDropKey = "";
    /** "NPC price -" lines: coins to buy every raw material at NPCs, and what NPCs pay for them. */
    private volatile List<String> npcPriceLines = List.of();
    /** {@link #npcPriceLines} ready for the HUD section, made when they are. */
    private volatile List<Line> npcPriceHudLines = List.of();
    /** Update thread only: {@link NpcPrices#getVersion()} the price lines were worked out with. */
    private long npcPriceVersion = -1;
    /**
     * Changes whenever the list is replaced wholesale (profile switch, data reset). An update that started on the
     * old list sees the change and neither publishes its results nor removes entries from the new list.
     */
    private volatile long listGeneration = 0;
    /** Update thread only: every item name in the current shopping-list trees. */
    private Set<String> listItemNames = Set.of();
    /** Update thread only: forge version and minute the forging lines were built for. */
    private long forgingVersion = -1;
    private long forgingMinute = -1;
    /** Update thread only: Quick Forge / mayor bonus version the forge times were worked out with. */
    private long forgeSpeedVersion = -1;
    /** Update thread only: recipe version the tree was worked out with (the recipe download replaces them). */
    private long recipeVersion = -1;
    /** Update thread only: a recipe has everything but something in its tree is still cooking in the Forge. */
    private boolean waitingOnForge = false;
    private volatile boolean showTotal = true;
    /** Read only: the Total setting before levels (Recipe ingredients is level 1). */
    private enum TotalMode { RAW, INGREDIENTS }
    /** Deepest Total level the setting offers. */
    public static final int MAX_TOTAL_LEVEL = 5;
    /** What the Total adds up: 0 = raw materials, N = the ingredients N steps below each recipe on the list. */
    private volatile int totalLevel = 0;
    /** Channel per notification type; a type missing here uses its default. */
    private final Map<Notifier.Type, Notifier.Channel> notifyChannels = new ConcurrentHashMap<>();
    private volatile boolean autoRemove = true;
    /** Held stock goes to the deepest steps of a tree first, instead of the first row that needs it. */
    private volatile boolean stockDeepestFirst = false;
    private volatile boolean itemListButton = true;
    private volatile int maxRecipes = 3;
    /** Where the HUD panels were last drawn (GUI pixels, borders included); empty while the HUD is hidden. */
    private volatile List<Rect2i> drawnPanels = List.of();
    /** Update thread only: entries already announced as ready / achieved, so each toast fires once. */
    private final Set<String> readyEntries = new HashSet<>();
    private final Set<String> achievedEntries = new HashSet<>();
    /** Update thread only: the first computation after start-up sets the baseline without toasting. */
    private boolean baselineSet = false;
    private final ResourcesManager resourcesManager;
    private final ScheduledExecutorService scheduler;
    /** Update thread only: resource version the current tree was computed from. */
    private long computedVersion = -1;
    /** Bumped by every {@link #requestRefresh()}; the update thread recomputes when it moved since it last looked. */
    private final AtomicLong refreshRequests = new AtomicLong();
    /** Update thread only: {@link #refreshRequests} as of the last recompute. */
    private long handledRequests = -1;
    /** Update thread only: {@link SackReader#getScanVersion()} the current tree was computed with. */
    private long sackScanVersion = -1;
    /** Sacks the list still needs read (unscanned sacks holding an item still missing), in order first needed. */
    private volatile List<String> neededSacks = List.of();
    /** All sacks holding an item still missing, read or not; with {@link #neededSacks} gives "1/3 read". */
    private volatile List<String> listSacks = List.of();
    /**
     * Name the unread sacks (chat or toast, see {@link Notifier.Type#SACK_PROMPT}) on the next list update: set on
     * joining (start of the session), when a recipe is added or its amount raised and when a saved list is loaded;
     * cleared once sent. Count changes alone never prompt.
     */
    private volatile boolean sackPromptDue = true;
    /**
     * The sacks named last time, so raising amounts again doesn't repeat the same notice. Cleared when a whole list
     * is loaded, which always names them, and once no sack is left to open (sacks read, list emptied or reset), so
     * the same sacks are named again when they are needed later.
     */
    private volatile List<String> lastPromptedSacks = List.of();
    /** Grey for amounts that depend on a sack not read yet. */
    private static final int UNCERTAIN_COLOR = 0xFF9A9A9A;
    /** Render thread only: row size and panel width of the HUD being drawn. */
    private int currentNodeLineHeight = 16;
    private int currentRowGap = 0;
    private float currentTreeScale = 1.0f;
    private int currentPanelWidth = 250;
    /** Render thread only: the tree level drawn with no indent (1 in the Total panel, whose rows are level 1). */
    private int currentBaseLevel = 0;

    private SandboxWidget() {
        this.resourcesManager = ResourcesManager.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ShoppingList-HudUpdate");
            t.setDaemon(true);
            return t;
        });
        HudElementRegistry.addLast(SANDBOX_WIDGET_LAYER, (context, tickCounter) -> {
            if (enabled && !shoppingList.isEmpty() && recipeTree != null && SkyblockDetector.isOnSkyblock()) {
                render(context);
            } else {
                drawnPanels = List.of();
            }
        });
        loadConfiguration();
        scheduler.scheduleWithFixedDelay(this::refreshIfStale, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Made on first use, after every static field of SandboxWidget is set: the constructor loads the list with
     * {@link #SHOPPING_LIST_TYPE}, which would still be null if the instance were a static field declared above it.
     */
    private static final class Holder {
        static final SandboxWidget INSTANCE = new SandboxWidget();
    }

    public static SandboxWidget getInstance() {
        return Holder.INSTANCE;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        requestRefresh();
        saveConfiguration();
    }
    public boolean isEnabled() {
        return this.enabled;
    }
    public enum AddResult { ADDED, INCREASED, FULL, WAITING }
    /** Lang key of the message shown while the profile isn't known yet. */
    public static final String WAITING_MESSAGE = "skyblock-shopping-list.waiting";

    /**
     * False for the few seconds after a server change on SkyBlock, before Hypixel names the profile: the list shown
     * still belongs to the profile being left, so an edit then would land there. Tells the player why.
     */
    private static boolean editable() {
        if (!SkyblockDetector.isOnSkyblock() || ProfileManager.isReady()) return true;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendOverlayMessage(Component.translatable(WAITING_MESSAGE));
        return false;
    }

    /** Adds a recipe, or raises its amount if it is already on the list. Refused when the list is full. */
    public synchronized AddResult addToList(String recipe, int amount) {
        if (!editable()) return AddResult.WAITING;
        amount = Math.max(1, amount);
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe)) {
                shoppingList.set(i, new ShoppingListEntry(recipe, e.amount + amount, e.startCount, e.isHaveTotal()));
                sackPromptDue = true;
                listChanged();
                return AddResult.INCREASED;
            }
        }
        if (shoppingList.size() >= maxRecipes) return AddResult.FULL;
        shoppingList.add(new ShoppingListEntry(recipe, amount, resourcesManager.getResourceByName(recipe)));
        expandedNodes.putIfAbsent(makePathKey(LIST_KEY, recipe), true);
        sackPromptDue = true;
        listChanged();
        return AddResult.ADDED;
    }
    public synchronized void setEntryAmount(String recipe, int amount) {
        if (!editable()) return;
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe) && e.amount != Math.max(1, amount)) {
                shoppingList.set(i, new ShoppingListEntry(recipe, Math.max(1, amount), e.startCount, e.isHaveTotal()));
                if (amount > e.amount) sackPromptDue = true;
                listChanged();
                return;
            }
        }
    }
    /**
     * After counts were corrected (item to how far they moved): Add more entries for those items move their starting
     * point the same way, so a fixed count isn't taken as copies made or lost. Not a player edit, so always allowed.
     */
    public synchronized void shiftStartCounts(Map<String, Integer> corrections) {
        Map<String, Integer> byName = new HashMap<>();
        corrections.forEach((name, delta) -> byName.merge(ItemNames.normalize(name), delta, Integer::sum));
        boolean changed = false;
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            Integer delta = byName.get(ItemNames.normalize(e.recipe));
            if (e.isHaveTotal() || delta == null || delta == 0) continue;
            shoppingList.set(i, new ShoppingListEntry(e.recipe, e.amount, e.startCount + delta, false));
            changed = true;
        }
        if (changed) listChanged();
    }
    /** Switches one entry between Have total (held copies count) and Add more (make this many more). */
    public synchronized void setEntryHaveTotal(String recipe, boolean haveTotal) {
        if (!editable()) return;
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe) && e.isHaveTotal() != haveTotal) {
                // Add more counts from now: what is held when switching is the new starting point.
                int start = haveTotal ? e.startCount : resourcesManager.getResourceByName(recipe);
                shoppingList.set(i, new ShoppingListEntry(recipe, e.amount, start, haveTotal));
                listChanged();
                return;
            }
        }
    }
    /**
     * Moves an entry up (negative) or down (positive) the list. Order is priority: entries higher up get shared
     * stock first.
     */
    public synchronized void moveEntry(String recipe, int delta) {
        if (!editable()) return;
        for (int i = 0; i < shoppingList.size(); i++) {
            if (!shoppingList.get(i).recipe.equals(recipe)) continue;
            int target = i + delta;
            if (target < 0 || target >= shoppingList.size()) return;
            ShoppingListEntry moved = shoppingList.get(i);
            shoppingList.set(i, shoppingList.get(target));
            shoppingList.set(target, moved);
            listChanged();
            return;
        }
    }
    public synchronized void removeFromList(String recipe) {
        if (!editable()) return;
        if (shoppingList.removeIf(e -> e.recipe.equals(recipe))) listChanged();
    }
    /**
     * Auto-remove: takes achieved entries off the list the update was worked out for and shows its pop-ups, if
     * that list is still current.
     */
    private synchronized void removeAchieved(List<String> recipes, List<Runnable> toasts, long generation) {
        if (generation != listGeneration) return;
        toasts.forEach(Runnable::run);
        if (shoppingList.removeIf(e -> recipes.contains(e.recipe))) listChanged();
    }
    /** What {@link #replaceList} left off: entries over the recipe limit, and Have total entries already done. */
    public record LoadResult(int dropped, int skipped) {}

    /**
     * Replaces the list with a saved one (List tab > Saved lists). Add more entries count from what is held now.
     * Have total entries you already hold (finished, not cooking) are left off, so loading doesn't announce and
     * auto-remove them again every time. Returns what was left off, or null if nothing changed.
     */
    public synchronized LoadResult replaceList(List<ShoppingListEntry> entries) {
        if (!editable()) return null;
        shoppingList.clear();
        Map<String, Integer> cooking = cookingCounts();
        int dropped = 0;
        int skipped = 0;
        for (ShoppingListEntry e : entries) {
            if (shoppingList.stream().anyMatch(s -> s.recipe.equals(e.recipe))) continue;
            int held = resourcesManager.getResourceByName(e.recipe);
            int amount = Math.max(1, e.amount);
            if (e.isHaveTotal() && held - cooking.getOrDefault(ItemNames.normalize(e.recipe), 0) >= amount) {
                skipped++;
                continue;
            }
            if (shoppingList.size() >= maxRecipes) {
                dropped++;
                continue;
            }
            shoppingList.add(new ShoppingListEntry(e.recipe, amount, held, e.isHaveTotal()));
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, e.recipe), true);
        }
        lastPromptedSacks = List.of();
        sackPromptDue = true;
        listChanged();
        return new LoadResult(dropped, skipped);
    }
    public synchronized void clearList() {
        if (!editable()) return;
        if (shoppingList.isEmpty()) return;
        shoppingList.clear();
        listChanged();
    }
    public String getCurrentSavedList() {
        return currentSavedList;
    }
    public void setCurrentSavedList(String name) {
        currentSavedList = name == null ? "" : name;
    }
    public List<ShoppingListEntry> getShoppingList() {
        List<ShoppingListEntry> copy = new ArrayList<>();
        for (ShoppingListEntry e : shoppingList) copy.add(e.copy());
        return copy;
    }
    /** Every list change: an update worked out for the list before it is thrown away, then a fresh one is queued. */
    private void listChanged() {
        listGeneration++;
        pruneExpandedNodes();
        requestRefresh();
        saveConfiguration();
        saveShoppingList();
    }
    /** Forgets which rows were expanded under recipes no longer on the list, so widget_config.json doesn't grow forever. */
    private void pruneExpandedNodes() {
        Set<String> roots = new HashSet<>();
        roots.add("Total");
        for (ShoppingListEntry e : shoppingList) roots.add(e.recipe);
        String prefix = LIST_KEY + ">";
        expandedNodes.keySet().removeIf(key -> {
            if (!key.startsWith(prefix)) return false;
            int end = key.indexOf('>', prefix.length());
            return !roots.contains(end < 0 ? key.substring(prefix.length()) : key.substring(prefix.length(), end));
        });
    }
    /** The list lives with the SkyBlock profile (shopping_list.json in its folder). */
    private synchronized void saveShoppingList() {
        List<ShoppingListEntry> list = getShoppingList();
        JsonFiles.writeAsync(FilePathManager.shoppingListJson(), () -> list);
    }
    private synchronized void loadShoppingList() {
        shoppingList.clear();
        List<ShoppingListEntry> saved = JsonFiles.read(FilePathManager.shoppingListJson(), SHOPPING_LIST_TYPE);
        if (saved == null) return;
        for (ShoppingListEntry e : saved) {
            if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                shoppingList.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), e.startCount, e.isHaveTotal()));
            }
        }
    }
    /**
     * Profile switch: runs {@code switchProfile} (points the data files at the new profile and reloads its counts)
     * and then loads that profile's list, all under this object's lock so a list save can't land in the wrong
     * folder. The generation changes before and after, so an update that started before or during the switch is
     * thrown away; one that starts after sees the new counts and list together. Pop-ups are re-baselined so the
     * switch itself doesn't announce anything.
     */
    public synchronized void reloadShoppingList(Runnable switchProfile) {
        listGeneration++;
        switchProfile.run();
        loadShoppingList();
        currentSavedList = "";
        lastPromptedSacks = List.of();
        listGeneration++;
        scheduler.execute(() -> {
            readyEntries.clear();
            achievedEntries.clear();
            baselineSet = false;
        });
        requestRefresh();
    }
    /** HUD heading. The recipes themselves are the top-level rows underneath. */
    public String getTitle() {
        return I18n.get("skyblock-shopping-list.hud.title");
    }
    /** The synthetic root the HUD draws (children: Total, then one tree per recipe), or null. */
    public RecipeManager.RecipeNode getDisplayRoot() {
        return recipeTree;
    }

    /** "Buy: ..." and "Sell: ..." for the whole list at NPC prices; empty when nothing has a price. */
    public List<String> getNpcPriceLines() {
        return npcPriceLines;
    }
    public boolean isNodeExpanded(String nodeKey) {
        Boolean result = expandedNodes.getOrDefault(nodeKey, false);
        return result != null ? result : false;
    }
    public void toggleNodeExpansion(String nodeKey) {
        Boolean currentState = expandedNodes.getOrDefault(nodeKey, false);
        if (currentState == null) {
            currentState = false;
        }

        boolean newState = !currentState;
        expandedNodes.put(nodeKey, newState);
        saveConfiguration();
    }
    public synchronized void saveConfiguration() {
        WidgetConfig config = new WidgetConfig();
        config.enabled = enabled;
        config.widgetX = widgetX;
        config.widgetY = widgetY;
        config.widgetRefWidth = widgetRefWidth;
        config.widgetRefHeight = widgetRefHeight;
        config.widgetWidth = widgetWidth;
        config.widgetHeight = widgetHeight;
        config.widgetScale = widgetScale;
        config.craftablePanel = craftablePanel;
        config.forgingPanel = forgingPanel;
        config.totalPanel = totalPanel;
        config.mobDropsPanel = mobDropsPanel;
        config.expandedNodes = new HashMap<>(expandedNodes);
        config.showTotal = showTotal;
        config.totalLevel = totalLevel;
        config.notify = new LinkedHashMap<>();
        for (Notifier.Type type : Notifier.Type.values()) config.notify.put(type.name(), getNotifyChannel(type));
        config.autoRemove = autoRemove;
        config.stockDeepestFirst = stockDeepestFirst;
        config.itemListButton = itemListButton;
        config.debugLogging = ShoppingList.debugLogging;
        config.maxRecipes = maxRecipes;
        JsonFiles.writeAsync(FilePathManager.WIDGET_CONFIG_JSON, () -> config);
    }
    private void loadConfiguration() {
        WidgetConfig config = JsonFiles.read(FilePathManager.WIDGET_CONFIG_JSON, WidgetConfig.class);
        if (config != null) loadWidgetConfig(config);
        loadShoppingList();
    }
    private void loadWidgetConfig(WidgetConfig config) {
        this.enabled = config.enabled;
        this.widgetX = config.widgetX;
        this.widgetY = config.widgetY;
        this.widgetRefWidth = config.widgetRefWidth;
        this.widgetRefHeight = config.widgetRefHeight;
        if (config.expandedNodes != null) {
            this.expandedNodes = new ConcurrentHashMap<>(config.expandedNodes);
        }
        // "Show remaining" became Settings > Appearance > Amount format; carry an old OFF over once.
        if (Boolean.FALSE.equals(config.showRemaining) && !HudStyle.isSaved()) {
            HudStyle.get().amountFormat = HudStyle.AmountFormat.REQUIRED;
            HudStyle.save();
        }
        if (config.showTotal != null) this.showTotal = config.showTotal;
        if (config.totalLevel != null) this.totalLevel = clampTotalLevel(config.totalLevel);
        else if (config.totalMode == TotalMode.INGREDIENTS) this.totalLevel = 1;
        if (config.notify != null) {
            for (Notifier.Type type : Notifier.Type.values()) {
                Notifier.Channel channel = config.notify.get(type.name());
                if (channel != null) notifyChannels.put(type, channel);
            }
        } else {
            // The on/off toggles from before 4.30.6: off becomes Off, on keeps the type's default channel.
            if (Boolean.FALSE.equals(config.notifications)) {
                notifyChannels.put(Notifier.Type.ACHIEVED, Notifier.Channel.OFF);
                notifyChannels.put(Notifier.Type.READY, Notifier.Channel.OFF);
            }
            if (Boolean.FALSE.equals(config.staleSackWarning)) {
                notifyChannels.put(Notifier.Type.STALE_SACKS, Notifier.Channel.OFF);
            }
        }
        if (config.autoRemove != null) this.autoRemove = config.autoRemove;
        if (config.stockDeepestFirst != null) this.stockDeepestFirst = config.stockDeepestFirst;
        if (config.itemListButton != null) this.itemListButton = config.itemListButton;
        ShoppingList.debugLogging = Boolean.TRUE.equals(config.debugLogging);
        if (config.maxRecipes != null) this.maxRecipes = clampMaxRecipes(config.maxRecipes);
        if (config.widgetWidth > 0) this.widgetWidth = config.widgetWidth;
        if (config.widgetHeight > 0) this.widgetHeight = config.widgetHeight;
        if (config.widgetScale != null) this.widgetScale = clampPanelScale(config.widgetScale);
        if (config.craftablePanel != null) this.craftablePanel = config.craftablePanel;
        if (config.forgingPanel != null) this.forgingPanel = config.forgingPanel;
        if (config.totalPanel != null) this.totalPanel = config.totalPanel;
        if (config.mobDropsPanel != null) this.mobDropsPanel = config.mobDropsPanel;
        // Before 4.22 the list was kept in widget_config.json: move it to its own file (with the other data
        // that ProfileManager hands to the first SkyBlock profile seen), then drop it from the config.
        if ((config.shoppingList != null || config.selectedRecipe != null) && !FilePathManager.shoppingListJson().exists()) {
            List<ShoppingListEntry> legacy = new ArrayList<>();
            if (config.shoppingList != null) {
                for (ShoppingListEntry e : config.shoppingList) {
                    if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                        // 4.20.6 had one list-wide switch (always saved); entries saved then take its value.
                        // Without it the config is older still, when every entry meant Add more.
                        boolean haveTotal = e.haveTotal != null ? e.haveTotal : Boolean.TRUE.equals(config.haveTotal);
                        legacy.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), e.startCount, haveTotal));
                    }
                }
            } else {
                // Config from before the shopping list: keep the one recipe it tracked, as Add more like then.
                int amount = config.craftAmount != null && config.craftAmount > 0 ? config.craftAmount : 1;
                legacy.add(new ShoppingListEntry(config.selectedRecipe, amount,
                    resourcesManager.getResourceByName(config.selectedRecipe), false));
            }
            JsonFiles.write(FilePathManager.shoppingListJson(), legacy);
            saveConfiguration();
        }
    }
    /** Restores defaults after a reset deleted the config file. */
    public synchronized void resetConfiguration() {
        enabled = false;
        listGeneration++;
        shoppingList.clear();
        currentSavedList = "";
        recipeTree = null;
        widgetX = 10;
        widgetY = 40;
        widgetWidth = 250;
        widgetHeight = 300;
        widgetScale = 100;
        widgetRefWidth = 0;
        widgetRefHeight = 0;
        craftablePanel = new PanelRect(270, 40, 180, 150);
        forgingPanel = new PanelRect(270, 200, 180, 120);
        totalPanel = new PanelRect(270, 330, 180, 150);
        mobDropsPanel = new PanelRect(270, 490, 180, 100);
        expandedNodes = new ConcurrentHashMap<>();
        showTotal = true;
        totalLevel = 0;
        notifyChannels.clear();
        autoRemove = true;
        stockDeepestFirst = false;
        itemListButton = true;
        ShoppingList.debugLogging = false;
        maxRecipes = 3;
        messages = List.of();
        saveConfiguration();
        requestRefresh();
    }
    private static class WidgetConfig {
        boolean enabled;
        int widgetX;
        int widgetY;
        int widgetRefWidth;
        int widgetRefHeight;
        int widgetWidth;
        int widgetHeight;
        Integer widgetScale;
        PanelRect craftablePanel;
        PanelRect forgingPanel;
        PanelRect totalPanel;
        PanelRect mobDropsPanel;
        Map<String, Boolean> expandedNodes;
        /** Read only: where the list was kept before 4.22 (now shopping_list.json, per profile). */
        List<ShoppingListEntry> shoppingList;
        /** Read only, for configs written before the shopping list existed. */
        String selectedRecipe;
        /** Read only, for configs written before the shopping list existed. */
        Integer craftAmount;
        // Boxed so configs written before an option existed get its default.
        /** Read only, for configs written before Settings > Appearance existed. */
        Boolean showRemaining;
        Boolean showTotal;
        Integer totalLevel;
        /** Read only, for configs written before Total levels. */
        TotalMode totalMode;
        /** Read only: the Pop-ups toggle from before Settings > Notifications had a channel per type. */
        Boolean notifications;
        /** Channel per {@link Notifier.Type} name. */
        Map<String, Notifier.Channel> notify;
        Boolean autoRemove;
        Boolean stockDeepestFirst;
        /** Read only: the list-wide Have total / Add more switch from 4.20.6, now set per entry. */
        Boolean haveTotal;
        /** Read only: the Sack reminder toggle from before Settings > Notifications had a channel per type. */
        Boolean staleSackWarning;
        Boolean itemListButton;
        Boolean debugLogging;
        Integer maxRecipes;
    }
    /** Marks the tree stale; the update thread recomputes it within a second. */
    private void requestRefresh() {
        refreshRequests.incrementAndGet();
        scheduler.execute(this::refreshIfStale);
    }

    /** Runs on the update thread. Recomputes only when resources or the selection changed. */
    private void refreshIfStale() {
        try {
            MayorEvents.refreshIfDue();
            long version = resourcesManager.getVersion();
            long recipes = RecipeManager.getInstance().getVersion();
            // Read before recomputing: a request made while this runs is still seen as new next time.
            long requests = refreshRequests.get();
            long scans = SackReader.getInstance().getScanVersion();
            if (version != computedVersion || requests != handledRequests || ForgeSpeed.getVersion() != forgeSpeedVersion
                    || recipes != recipeVersion || scans != sackScanVersion || NpcPrices.getVersion() != npcPriceVersion) {
                computedVersion = version;
                npcPriceVersion = NpcPrices.getVersion();
                sackScanVersion = scans;
                handledRequests = requests;
                forgeSpeedVersion = ForgeSpeed.getVersion();
                recipeVersion = recipes;
                updateRecipeData();
            }
            // Forge countdowns move every minute even when nothing else changes.
            long minute = System.currentTimeMillis() / 60_000;
            if (ForgeTracker.getVersion() != forgingVersion || minute != forgingMinute) {
                boolean forgeChanged = ForgeTracker.getVersion() != forgingVersion;
                forgingVersion = ForgeTracker.getVersion();
                forgingMinute = minute;
                // A held-back "Ready to craft" may be due now that time passed or the forge changed.
                if (waitingOnForge || forgeChanged) {
                    updateRecipeData();
                } else {
                    updateForgingLines();
                }
            }
            // Kills come in between list changes; the settings can change without one too.
            HudStyle style = HudStyle.get();
            String mobDrops = KillTracker.getVersion() + "|" + MobDrops.getVersion() + "|" + style.showMobDrops + "|"
                + style.killCases + "|" + style.magicFind + "|" + style.shortNumbers;
            if (!mobDrops.equals(mobDropKey)) {
                mobDropKey = mobDrops;
                updateMobDropLines();
            }
        } catch (RuntimeException e) {
            // Never let an exception escape: it would cancel the scheduled refresh for good.
            ShoppingList.LOGGER.error("Failed to update HUD recipe", e);
        }
    }

    private void updateRecipeData() {
        // Read before the entries: a switch in between makes this update stale, never the other way round.
        long generation = listGeneration;
        List<ShoppingListEntry> entries = getShoppingList();
        if (entries.isEmpty()) {
            recipeTree = null;
            messages = List.of();
            listItemNames = Set.of();
            forgingLines = ForgingLines.EMPTY;
            mobDropLines = List.of();
            KillTracker.setTargets(Set.of());
            npcPriceLines = List.of();
            npcPriceHudLines = List.of();
            neededSacks = List.of();
            listSacks = List.of();
            lastPromptedSacks = List.of();
            readyEntries.clear();
            achievedEntries.clear();
            baselineSet = true;
            return;
        }

        boolean deepestFirst = stockDeepestFirst;
        ResourcesManager.ShoppingResponse response = resourcesManager.getShoppingList(entries, deepestFirst);
        // Memo for the stock order, so cooking copies are marked on the rows that got the stock.
        Map<String, Integer> heights = deepestFirst ? new HashMap<>() : null;
        boolean announce = baselineSet;
        baselineSet = true;

        List<String> toRemove = new ArrayList<>();
        // Pop-ups wait until the results are known to belong to the current list. The ready/achieved sets
        // themselves are cleared by a profile switch after this update ends (it is queued behind it).
        List<Runnable> toasts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> cooking = cookingCounts();
        // Finished (not cooking) stock left per item, shared out in list order like the shopping list does.
        Map<String, Integer> finishedLeft = new HashMap<>();
        waitingOnForge = false;
        for (int i = 0; i < entries.size(); i++) {
            ShoppingListEntry entry = entries.get(i);
            RecipeManager.RecipeNode tree = response.trees.get(i);
            names.add(entry.recipe);
            // Have total: done once you hold the amount. Add more: once you hold that many more than when added.
            // Copies still cooking in the Forge are counted as held but aren't made yet, so they don't count here.
            int target = (entry.isHaveTotal() ? 0 : entry.startCount) + entry.amount;
            int held = resourcesManager.getResourceByName(entry.recipe);
            int finished = held - cooking.getOrDefault(ItemNames.normalize(entry.recipe), 0);
            boolean achieved = finished >= target;
            if (!achieved && finished < held) {
                // Copies cooking now count towards this entry: it waits on the Forge (and shows its timer).
                tree.cooking = true;
                // Only the Forge is left: check again each minute until it is done.
                if (held >= target) waitingOnForge = true;
            }
            if (achieved) {
                if (achievedEntries.add(entry.recipe) && announce) {
                    Component message = Component.translatable("skyblock-shopping-list.notify.amount", entry.amount, entry.recipe);
                    toasts.add(() -> Notifier.send(Notifier.Type.ACHIEVED,
                        Component.translatable("skyblock-shopping-list.notify.achieved"), message));
                }
                if (autoRemove) toRemove.add(entry.recipe);
                continue;
            }
            achievedEntries.remove(entry.recipe);
            boolean ready = tree.ingredients != null && !tree.ingredients.isEmpty()
                && tree.ingredients.stream().allMatch(child -> child.amount <= 0);
            // Forge items count as owned while cooking, but can't be used until they are done: hold the pop-up
            // only if this recipe needs more of an item than is already finished.
            if (!cooking.isEmpty() && needsCooking(tree, cooking, finishedLeft, heights) && ready) {
                ready = false;
                waitingOnForge = true;
            }
            if (!ready) {
                readyEntries.remove(entry.recipe);
            } else if (readyEntries.add(entry.recipe) && announce) {
                Component message = Component.translatable("skyblock-shopping-list.notify.amount", entry.amount, entry.recipe);
                toasts.add(() -> Notifier.send(Notifier.Type.READY,
                    Component.translatable("skyblock-shopping-list.notify.ready"), message));
            }
        }
        readyEntries.retainAll(names);
        achievedEntries.retainAll(names);
        if (!toRemove.isEmpty()) {
            // Removing schedules another refresh, which redraws without these entries.
            removeAchieved(toRemove, toasts, generation);
            return;
        }

        List<String> newMessages = new ArrayList<>();
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(response.craftable.entrySet());
        sortedEntries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        for (Map.Entry<String, Integer> entry : sortedEntries) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                newMessages.add(I18n.get("skyblock-shopping-list.notify.amount", entry.getValue(), entry.getKey()));
            }
        }

        Map<String, Long> cookingEnds = cookingEnds();
        double forgeMultiplier = ForgeSpeed.multiplier();
        for (RecipeManager.RecipeNode tree : response.trees) setForgeTimes(tree, cookingEnds, forgeMultiplier);
        RecipeManager recipeManager = RecipeManager.getInstance();
        for (RecipeManager.RecipeNode tree : response.trees) setLocks(tree, recipeManager, 0);

        List<RecipeManager.RecipeNode> tops = new ArrayList<>();
        int level = totalLevel;
        RecipeManager.RecipeNode total = level == 0 ? response.total : ResourcesManager.levelTotalOf(response.trees, response.held, level);
        if (showTotal && !total.ingredients.isEmpty()) {
            tops.add(total);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, total.name), true);
        }
        for (RecipeManager.RecipeNode tree : response.trees) {
            tops.add(tree);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, tree.name), true);
        }
        SackReader sacks = SackReader.getInstance();
        for (RecipeManager.RecipeNode top : tops) setUncertain(top, sacks, 0);
        for (RecipeManager.RecipeNode top : tops) setProgress(top, 0);
        Set<String> itemNames = new HashSet<>();
        for (RecipeManager.RecipeNode tree : response.trees) collectNames(tree, itemNames);
        updateNeededSacks(response.trees, toasts);
        // Every raw material the list needs, held or not: the whole bill at NPC prices. Entries with no recipe
        // (e.g. 100k Titanium) are raw materials themselves; the Total only holds leaves under recipes.
        List<RecipeManager.RecipeNode> priced = new ArrayList<>(response.total.ingredients);
        for (RecipeManager.RecipeNode tree : response.trees) {
            if (tree.ingredients == null || tree.ingredients.isEmpty()) priced.add(tree);
        }
        List<String> prices = NpcPrices.summary(priced);
        if (!publish(generation, newMessages, new RecipeManager.RecipeNode("Shopping list", 0, 0, tops),
                itemNames, prices, toasts)) {
            return;
        }
        updateForgingLines();
        updateMobDropLines();
    }

    /**
     * Works out which sacks hold items the list is still missing, and which of those haven't been read. When a
     * prompt is due (after joining, or adding a recipe) the unread ones are named in one notification.
     */
    private void updateNeededSacks(List<RecipeManager.RecipeNode> trees, List<Runnable> toasts) {
        Set<String> missing = new LinkedHashSet<>();
        for (RecipeManager.RecipeNode tree : trees) collectMissing(tree, missing, 0);
        RecipeManager recipes = RecipeManager.getInstance();
        Set<String> all = new LinkedHashSet<>();
        for (String name : missing) {
            String sack = recipes.getSack(name);
            if (!sack.isEmpty()) all.add(sack);
        }
        List<String> unread = SackReader.getInstance().unscannedSacks(missing);
        neededSacks = List.copyOf(unread);
        listSacks = List.copyOf(all);
        if (unread.isEmpty()) lastPromptedSacks = List.of();
        if (sackPromptDue && SkyblockDetector.isOnSkyblock() && ProfileManager.isReady()) {
            // Runs only if this update is published; the check keeps two queued updates from both sending it.
            toasts.add(() -> {
                if (!sackPromptDue) return;
                sackPromptDue = false;
                if (unread.equals(lastPromptedSacks)) return;
                lastPromptedSacks = List.copyOf(unread);
                ReminderManager.promptSacks(unread);
            });
        }
    }

    /** Fills {@link RecipeManager.RecipeNode#uncertain} for this row and every row under it. */
    private static void setUncertain(RecipeManager.RecipeNode node, SackReader sacks, int depth) {
        if (node == null || depth > RecipeManager.MAX_DEPTH) return;
        node.uncertain = (node.amount > 0 || node.toCraft > 0) && sacks.isUncertain(node.name);
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) setUncertain(child, sacks, depth + 1);
        }
    }

    /**
     * Fills {@link RecipeManager.RecipeNode#progress} for this row and every row under it. Returns the raw materials
     * this row stands for, {have, need}, so the row above weighs its ingredients by raw count: 0/1 Flawless Ruby over
     * 21/80 Fine Ruby is 26% done, like the Fine Ruby row. Ingredients only cover what is still left to get or make,
     * so the part of a row already held counts as done.
     */
    private static double[] setProgress(RecipeManager.RecipeNode node, int depth) {
        if (node == null) return new double[] {0, 0};
        double required = Math.max(0, node.required);
        double left = Math.min(required, Math.max(0L, (long) node.amount + node.toCraft));
        double childHave = 0;
        double childNeed = 0;
        if (node.ingredients != null && depth < RecipeManager.MAX_DEPTH) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                double[] raw = setProgress(child, depth + 1);
                childHave += raw[0];
                childNeed += raw[1];
            }
        }
        if (required <= 0 || left <= 0) {
            node.progress = 1f;
            return new double[] {required, required};
        }
        if (childNeed <= 0) {
            // A raw material (or a row with nothing expanded under it): what is held of what is needed.
            node.progress = (float) ((required - left) / required);
            return new double[] {required - left, required};
        }
        node.progress = (float) ((required - left) / required + left / required * (childHave / childNeed));
        // Raw materials for the whole row: the ingredients cover the part left, so scale them up to all of it.
        double need = childNeed * required / left;
        return new double[] {need * node.progress, need};
    }

    private static void collectMissing(RecipeManager.RecipeNode node, Set<String> out, int depth) {
        if (node == null || depth > RecipeManager.MAX_DEPTH) return;
        if (node.amount > 0 || node.toCraft > 0) out.add(node.name);
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) collectMissing(child, out, depth + 1);
        }
    }

    /** Every sack holding an item the list is still missing, read or not. */
    public List<String> getListSacks() {
        return listSacks;
    }

    /** Sacks still to open for the current list, in the order first needed. */
    public List<String> getNeededSacks() {
        return neededSacks;
    }

    /** When the last Forge slot cooking each item (by normalised name) is done, for items still cooking. */
    private static Map<String, Long> cookingEnds() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            if (entry.endsAt > now) out.merge(ItemNames.normalize(entry.name), entry.endsAt, Math::max);
        }
        return out;
    }

    /**
     * Fills in each step's forge time: every forge craft still to make under it, one after another (own time ×
     * how many are still to make, plus all its ingredients' times). Items cooking now add the time they have
     * left; crafts not started add their full time, cut by Quick Forge and the mayor bonus.
     */
    private static void setForgeTimes(RecipeManager.RecipeNode node, Map<String, Long> cookingEnds, double multiplier) {
        // Item -> finish time, so an item cooking once is counted once even if several steps need it.
        Map<String, Long> cooking = new HashMap<>();
        collectForgeTimes(node, cookingEnds, multiplier, cooking);
    }

    private static long collectForgeTimes(RecipeManager.RecipeNode node, Map<String, Long> cookingEnds, double multiplier,
                                          Map<String, Long> cooking) {
        Map<String, Long> own = new HashMap<>();
        String key = ItemNames.normalize(node.name);
        Long cookingEnd = cookingEnds.get(key);
        // Only rows that wait on the Forge get its timer; one met from finished stock ignores a copy cooking for
        // something else. node.cooking is set before this runs (useStock, and the entry check for list rows).
        if (cookingEnd != null && node.cooking) own.put(key, cookingEnd);
        long ms = 0;
        int toMake = node.amount + node.toCraft;
        if (toMake > 0) {
            ms = Math.round(RecipeManager.getInstance().getForgeSeconds(node.name) * 1000L * multiplier) * toMake;
            if (node.ingredients != null) {
                for (RecipeManager.RecipeNode child : node.ingredients) {
                    ms += collectForgeTimes(child, cookingEnds, multiplier, own);
                }
            }
        }
        node.forgeMs = ms;
        node.forgeCookingEnds = own.values().stream().mapToLong(Long::longValue).toArray();
        cooking.putAll(own);
        return ms;
    }

    /** How many of each Forge item (by normalised name) are still cooking. */
    private static Map<String, Integer> cookingCounts() {
        long now = System.currentTimeMillis();
        Map<String, Integer> out = new HashMap<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            if (entry.endsAt > now) out.merge(ItemNames.normalize(entry.name), entry.count, Integer::sum);
        }
        return out;
    }

    /**
     * True if the tree takes more of a cooking item from stock than is already finished. Uses up
     * {@code finishedLeft} as it goes (every node is visited), so later recipes only get what is left. The root
     * is skipped: it never draws on stock of itself. Rows are visited in the order they claimed stock
     * ({@code heights} as in {@link ResourcesManager#stockOrder}).
     */
    private boolean needsCooking(RecipeManager.RecipeNode root, Map<String, Integer> cooking, Map<String, Integer> finishedLeft,
                                 Map<String, Integer> heights) {
        boolean short_ = false;
        if (root.ingredients != null) {
            for (RecipeManager.RecipeNode child : inStockOrder(root.ingredients, heights)) {
                if (useStock(child, cooking, finishedLeft, heights)) short_ = true;
            }
        }
        return short_;
    }

    /** A row's children in the order they claim held stock (as listed when {@code heights} is null). */
    private static List<RecipeManager.RecipeNode> inStockOrder(List<RecipeManager.RecipeNode> children,
                                                               Map<String, Integer> heights) {
        if (heights == null) return children;
        List<String> names = children.stream().map(c -> c.name).toList();
        List<RecipeManager.RecipeNode> out = new ArrayList<>();
        for (int k : ResourcesManager.stockOrder(names, RecipeManager.getInstance().getAllRecipes(), heights)) {
            out.add(children.get(k));
        }
        return out;
    }

    private boolean useStock(RecipeManager.RecipeNode node, Map<String, Integer> cooking, Map<String, Integer> finishedLeft,
                             Map<String, Integer> heights) {
        boolean short_ = false;
        String key = ItemNames.normalize(node.name);
        Integer cookingCount = cooking.get(key);
        if (cookingCount != null) {
            // What this node takes from items already held (not missing, not still to craft).
            int used = Math.max(0, node.required - node.amount - node.toCraft);
            int left = finishedLeft.computeIfAbsent(key,
                k -> Math.max(0, resourcesManager.getResourceByName(node.name) - cookingCount));
            if (used > left) {
                short_ = true;
                // Counted as held, but some of it is still in the Forge: not usable yet.
                node.cooking = true;
            }
            finishedLeft.put(key, Math.max(0, left - used));
        }
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : inStockOrder(node.ingredients, heights)) {
                if (useStock(child, cooking, finishedLeft, heights)) short_ = true;
            }
        }
        return short_;
    }

    private static void collectNames(RecipeManager.RecipeNode node, Set<String> out) {
        out.add(ItemNames.normalize(node.name));
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) collectNames(child, out);
        }
    }

    /**
     * Shows an update's results and pop-ups, unless the list was swapped while it ran (they belong to the old
     * list; the swap queued a fresh update). Under the lock the swap takes, so the check and the publish are one step.
     */
    private synchronized boolean publish(long generation, List<String> newMessages, RecipeManager.RecipeNode tree,
                                         Set<String> itemNames, List<String> prices, List<Runnable> toasts) {
        if (generation != listGeneration) return false;
        messages = Line.plain(newMessages);
        npcPriceLines = prices;
        npcPriceHudLines = Line.plain(prices);
        recipeTree = tree;
        listItemNames = itemNames;
        toasts.forEach(Runnable::run);
        return true;
    }

    /**
     * Mob drops lines for items the list is still missing that a mob drops, and tells {@link KillTracker} which
     * items kills count towards.
     */
    private void updateMobDropLines() {
        HudStyle style = HudStyle.get();
        RecipeManager.RecipeNode tree = recipeTree;
        Set<String> missing = new LinkedHashSet<>();
        if (style.showMobDrops && tree != null) collectMissing(tree, missing, 0);
        int magicFind = KillTracker.magicFind();
        Set<String> targets = new HashSet<>();
        List<Line> lines = new ArrayList<>();
        for (String name : missing) {
            // The mob you've been killing for it, else the likeliest one.
            MobDrops.Source source = MobDrops.forMob(name, KillTracker.mob(name));
            if (source == null) source = MobDrops.best(name);
            if (source == null || !targets.add(ItemNames.normalize(name))) continue;
            lines.add(mobDropLine(name, source, KillTracker.kills(name), magicFind, style.killCases));
        }
        // At the bottom, so you can see which Magic Find the estimates use.
        if (style.showMobDrops) lines.add(new Line(KillTracker.magicFindLabel(), Tone.DONE));
        KillTracker.setTargets(targets);
        mobDropLines = List.copyOf(lines);
    }

    /**
     * "Ender Necklace (Zealot): 312 kills, best 15, you 88, worst 400 left". Best is lucky with your Magic Find
     * (a quarter of players have it by then), you is the average with it, worst the average with none. Past the
     * worst case the line is {@link Tone#OTHER} (overdue).
     */
    private static Line mobDropLine(String name, MobDrops.Source source, int kills, int magicFind,
                                    HudStyle.KillCases cases) {
        double yours = MobDrops.effectiveChance(source.chance, magicFind);
        long best = MobDrops.luckyKills(yours);
        long you = MobDrops.expectedKills(yours);
        long worst = MobDrops.expectedKills(source.chance);
        String left = switch (cases) {
            case BEST -> I18n.get("skyblock-shopping-list.hud.mob_drops.left", left(best, kills));
            case YOUR -> I18n.get("skyblock-shopping-list.hud.mob_drops.left", left(you, kills));
            case WORST -> I18n.get("skyblock-shopping-list.hud.mob_drops.left", left(worst, kills));
            case ALL -> I18n.get("skyblock-shopping-list.hud.mob_drops.left_all", left(best, kills), left(you, kills),
                left(worst, kills));
        };
        String text = I18n.get("skyblock-shopping-list.hud.mob_drops.line", name, source.mob, number(kills), left);
        return new Line(text, kills >= worst ? Tone.OTHER : Tone.TEXT);
    }

    private static String left(long target, int kills) {
        return number(Math.max(0, target - kills));
    }

    /** Forging lines: Listed has the forge slots making something on the list, All has those and then the rest. */
    private record ForgingLines(List<Line> listed, List<Line> all) {
        static final ForgingLines EMPTY = new ForgingLines(List.of(), List.of());
    }

    /** Forge slots grouped by item and finish minute, soonest first; items on the list before the rest. */
    private void updateForgingLines() {
        long now = System.currentTimeMillis();
        List<ForgeTracker.Entry> listed = new ArrayList<>();
        List<ForgeTracker.Entry> other = new ArrayList<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            (listItemNames.contains(ItemNames.normalize(entry.name)) ? listed : other).add(entry);
        }
        List<Line> listedLines = forgeLines(listed, now, true);
        List<Line> all = new ArrayList<>(listedLines);
        all.addAll(forgeLines(other, now, false));
        forgingLines = new ForgingLines(listedLines, List.copyOf(all));
    }

    /**
     * "4× Refined Titanium - 1h 8m": slots making the same item that finish in the same minute share a line.
     * Off-list slots are {@link Tone#OTHER} even when finished, so a ready slot the list doesn't need doesn't look
     * like one it does.
     */
    private static List<Line> forgeLines(List<ForgeTracker.Entry> entries, long now, boolean onList) {
        entries.sort(java.util.Comparator.comparingLong(e -> e.endsAt));
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, ForgeTracker.Entry> firstOf = new LinkedHashMap<>();
        for (ForgeTracker.Entry entry : entries) {
            String key = entry.name + "|" + ForgeTracker.formatRemaining(entry.endsAt, now);
            counts.computeIfAbsent(key, k -> new int[1])[0] += entry.count;
            firstOf.putIfAbsent(key, entry);
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            ForgeTracker.Entry entry = firstOf.get(e.getKey());
            String text = I18n.get("skyblock-shopping-list.hud.forging.line", e.getValue()[0], entry.name,
                ForgeTracker.formatRemaining(entry.endsAt, now));
            Tone tone = !onList ? Tone.OTHER : entry.endsAt <= now ? Tone.DONE : Tone.TEXT;
            lines.add(new Line(text, tone));
        }
        return List.copyOf(lines);
    }

    /** The HUD panels: the shopping list, and Craftable / Forging / Total when set to their own panel. */
    public enum Panel { MAIN, CRAFTABLE, FORGING, TOTAL, MOB_DROPS }

    /**
     * Where another mod has put a panel: top-left in GUI pixels, width and max height in HUD units, the scale to draw
     * at (HUD units -> GUI pixels), and whether it is hidden.
     */
    public record Placement(int x, int y, int width, int height, float scale, boolean hidden) {}

    /** Places the panels and opens the editor for them in place of the Move HUD screen (Huddable, when installed). */
    public interface PanelPlacer {
        /** This frame's placement, or null to use the panel's own. */
        Placement place(Panel panel);

        void openEditor(Screen parent);
    }

    /** Set by {@code compat.HuddableCompat}; null without Huddable. */
    private static volatile PanelPlacer placer;

    public static void setPlacer(PanelPlacer panelPlacer) {
        placer = panelPlacer;
    }

    /** Opens the editor that places the panels: Huddable's when it does, otherwise the Move HUD screen. */
    public static void openMoveHud(Screen parent) {
        PanelPlacer current = placer;
        if (current != null) current.openEditor(parent);
        else Minecraft.getInstance().gui.setScreen(new HudPositionScreen(parent));
    }

    /**
     * Position (GUI pixels), size (HUD units, before scaling) and scale (percent, on top of the HUD scale) of one
     * panel.
     */
    public static final class PanelRect {
        public int x;
        public int y;
        public int width;
        public int height;
        /** 0 in configs saved before panels had their own scale; read through {@link #scalePercent()}. */
        public int scale;
        /** GUI-scaled screen size x / y were saved at (0 = not recorded yet); positions follow the screen. */
        public int refWidth;
        public int refHeight;

        public PanelRect(int x, int y, int width, int height, int scale) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }

        public PanelRect(int x, int y, int width, int height) {
            this(x, y, width, height, 100);
        }

        public int scalePercent() {
            return scale > 0 ? clampPanelScale(scale) : 100;
        }
    }

    private static int clampTotalLevel(int level) {
        return Math.max(0, Math.min(MAX_TOTAL_LEVEL, level));
    }

    public static final int MIN_PANEL_SCALE = 25;
    public static final int MAX_PANEL_SCALE = 400;

    public static int clampPanelScale(int percent) {
        return Math.max(MIN_PANEL_SCALE, Math.min(MAX_PANEL_SCALE, percent));
    }

    /** Which colour a section line is drawn in: the section's text, its other colour, or the style's done colour. */
    private enum Tone { TEXT, OTHER, DONE }

    /** One section line and its colour. */
    private record Line(String text, Tone tone) {
        static List<Line> plain(List<String> texts) {
            List<Line> lines = new ArrayList<>(texts.size());
            for (String text : texts) lines.add(new Line(text, Tone.TEXT));
            return List.copyOf(lines);
        }
    }

    /**
     * One block of centred/aligned lines under a header (Craftable, Forging). {@link Tone#OTHER} lines use
     * {@code otherColor} (Forging slots the list doesn't need).
     */
    private record Section(String header, List<Line> lines, float scale, HudStyle.Align align,
                           int headerColor, int textColor, int otherColor) {
        Section(String header, List<Line> lines, float scale, HudStyle.Align align, int headerColor, int textColor) {
            this(header, lines, scale, align, headerColor, textColor, textColor);
        }
    }

    /** A piece of a section line after wrapping, with the colour of the line it came from. */
    private record WrappedLine(String text, Tone tone) {}

    private static final int TITLE_BAR = 20;

    /** True while {@link #renderPreview} draws: panels show the sample list below, fully expanded. */
    private boolean previewing = false;
    private static final RecipeManager.RecipeNode SAMPLE_TREE = sampleTree();
    // Sample lines are built when the preview draws, so they are in the current language.
    private static List<Line> sampleCraftable() {
        return Line.plain(List.of(I18n.get("skyblock-shopping-list.notify.amount", 1, "Refined Diamond")));
    }

    /** A listed Forging line, then one for an item not on the list. */
    private static List<Line> sampleForging() {
        return List.of(
            new Line(I18n.get("skyblock-shopping-list.hud.forging.line", 3, "Mithril Plate",
                I18n.get("skyblock-shopping-list.time.hours", 2, 10)), Tone.TEXT),
            new Line(I18n.get("skyblock-shopping-list.hud.forging.line", 1, "Refined Mithril",
                I18n.get("skyblock-shopping-list.time.hours", 5, 0)), Tone.OTHER));
    }

    /** A Mob drops line still in reach, then an overdue one. */
    private static List<Line> sampleMobDrops() {
        return List.of(
            new Line(I18n.get("skyblock-shopping-list.hud.mob_drops.line", "Ender Necklace", "Zealot", "312",
                I18n.get("skyblock-shopping-list.hud.mob_drops.left_all", 15, 88, 400)), Tone.TEXT),
            new Line(I18n.get("skyblock-shopping-list.hud.mob_drops.line", "Summoning Eye", "Zealot", "1,020",
                I18n.get("skyblock-shopping-list.hud.mob_drops.left_all", 0, 0, 0)), Tone.OTHER),
            new Line(I18n.get("skyblock-shopping-list.magic_find.from", 237,
                I18n.get("skyblock-shopping-list.magic_find.last_drop")), Tone.DONE));
    }

    private static List<Line> sampleNpcPrice() {
        return Line.plain(List.of(NpcPrices.line("skyblock-shopping-list.npc_price.buy", 1240, 2, 3),
            NpcPrices.line("skyblock-shopping-list.npc_price.sell", 310, 3, 3)));
    }

    /** A small made-up list showing every row state: done, partly gathered, missing and can craft. */
    private static RecipeManager.RecipeNode sampleTree() {
        RecipeManager.RecipeNode total = new RecipeManager.RecipeNode("Total", 512, 1282, new ArrayList<>(List.of(
            new RecipeManager.RecipeNode("Enchanted Diamond", 0, 2, null),
            new RecipeManager.RecipeNode("Mithril", 448, 960, null),
            new RecipeManager.RecipeNode("Gold Ingot", 64, 64, null))));
        RecipeManager.RecipeNode enchanted = new RecipeManager.RecipeNode("Enchanted Mithril", 0, 320, null);
        enchanted.toCraft = 160;
        RecipeManager.RecipeNode recipe = new RecipeManager.RecipeNode("Refined Mithril", 1, 2,
            new ArrayList<>(List.of(enchanted)));
        return new RecipeManager.RecipeNode("Shopping list", 0, 0, new ArrayList<>(List.of(total, recipe)));
    }

    /**
     * Draws the panels {@code style} would show, with sample rows, stacked from (x, y) and scaled to fit
     * {@code width} GUI pixels. Returns the height used. Settings > Appearance uses it to preview unsaved changes.
     * Render thread only.
     */
    public int renderPreview(GuiGraphicsExtractor context, int x, int y, int width, HudStyle style) {
        return HudStyle.withOverride(style, () -> {
            previewing = true;
            try {
                // Keep the outward panel border inside the column.
                int inset = style.outerInset();
                List<Panel> panels = activePanels();
                // One scale for every panel: as wide as the column allows, but short enough to leave room for the
                // description text under it.
                int widest = 1;
                int totalHeight = 0;
                for (Panel panel : panels) {
                    PanelRect rect = getPanelRect(panel);
                    widest = Math.max(widest, rect.width);
                    totalHeight += measurePanel(panel, rect.width);
                }
                int maxHeight = Math.round(Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.4f);
                int gaps = panels.size() * (2 * inset + 2);
                float scale = Math.min(1.5f, Math.min((width - 2f * inset) / widest,
                    (maxHeight - gaps) / (float) Math.max(1, totalHeight)));
                int cursor = y + inset;
                for (Panel panel : panels) {
                    PanelRect rect = getPanelRect(panel);
                    int height = renderPanel(context, panel, x + inset, cursor, rect.width, 4000, scale, true);
                    cursor += Math.round(height * scale) + 2 * inset + 2;
                }
                return cursor - y;
            } finally {
                previewing = false;
            }
        });
    }

    private boolean isExpandedForDraw(String nodeKey) {
        return previewing || expandedNodes.getOrDefault(nodeKey, false);
    }

    /**
     * HUD units -> GUI pixels. Window size: 100% is the original size in a 1080p window and grows and shrinks
     * with the window height. GUI Scale: follows Minecraft's GUI Scale. Fixed: same size on screen always.
     */
    public static float scaleFactor() {
        HudStyle style = HudStyle.get();
        float scale = style.hudScale / 100f;
        if (style.sizing == HudStyle.Sizing.GUI_SCALE) return scale;
        Window window = Minecraft.getInstance().getWindow();
        int guiScale = Math.max(1, window.getGuiScale());
        if (style.sizing == HudStyle.Sizing.FIXED) return scale * 2f / guiScale;
        return scale * 2f * (window.getScreenHeight() / 1080f) / guiScale;
    }

    /** HUD units -> GUI pixels for one panel: the HUD scale times that panel's own scale. */
    public float scaleFactor(Panel panel) {
        return scaleFactor() * storedRect(panel).scalePercent() / 100f;
    }

    /** The HUD scale times the panel's own scale, without HUD sizing: for Huddable, whose own sizing applies. */
    public float panelScale(Panel panel) {
        return HudStyle.get().hudScale / 100f * storedRect(panel).scalePercent() / 100f;
    }

    /**
     * Where a panel goes on the current screen: its saved position moved in proportion to how the screen size
     * changed since it was placed, then kept on screen.
     */
    public PanelRect getPanelRect(Panel panel) {
        PanelRect r = storedRect(panel);
        Window window = Minecraft.getInstance().getWindow();
        int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        int x = r.refWidth > 0 ? Math.round(r.x * (float) screenW / r.refWidth) : r.x;
        int y = r.refHeight > 0 ? Math.round(r.y * (float) screenH / r.refHeight) : r.y;
        int w = Math.round(r.width * scaleFactor() * r.scalePercent() / 100f);
        // Height is only a limit (panels are often shorter), so just keep the top in view.
        x = Math.max(0, Math.min(x, screenW - Math.min(w, screenW)));
        y = Math.max(0, Math.min(y, screenH - 20));
        PanelRect out = new PanelRect(x, y, r.width, r.height, r.scalePercent());
        out.refWidth = screenW;
        out.refHeight = screenH;
        return out;
    }

    /** The panel as saved, before fitting it to the current screen. */
    private PanelRect storedRect(Panel panel) {
        return switch (panel) {
            case MAIN -> {
                PanelRect r = new PanelRect(widgetX, widgetY, widgetWidth, widgetHeight, widgetScale);
                r.refWidth = widgetRefWidth;
                r.refHeight = widgetRefHeight;
                yield r;
            }
            case CRAFTABLE -> craftablePanel;
            case FORGING -> forgingPanel;
            case TOTAL -> totalPanel;
            case MOB_DROPS -> mobDropsPanel;
        };
    }

    public synchronized void setPanelRect(Panel panel, int x, int y, int width, int height, int scale) {
        int percent = clampPanelScale(scale);
        // No bigger than the screen (share codes and preset files can hold any size), never under the minimum.
        Window window = Minecraft.getInstance().getWindow();
        float factor = scaleFactor() * percent / 100f;
        int maxWidth = Math.max(minWidth(panel), (int) (window.getGuiScaledWidth() / factor));
        int maxHeight = Math.max(minHeight(panel), (int) (window.getGuiScaledHeight() / factor));
        PanelRect rect = new PanelRect(x, y, Math.min(maxWidth, Math.max(minWidth(panel), width)),
            Math.min(maxHeight, Math.max(minHeight(panel), height)), percent);
        setRef(rect);
        switch (panel) {
            case MAIN -> {
                widgetX = rect.x;
                widgetY = rect.y;
                widgetWidth = rect.width;
                widgetHeight = rect.height;
                widgetScale = rect.scale;
                widgetRefWidth = rect.refWidth;
                widgetRefHeight = rect.refHeight;
            }
            case CRAFTABLE -> craftablePanel = rect;
            case FORGING -> forgingPanel = rect;
            case TOTAL -> totalPanel = rect;
            case MOB_DROPS -> mobDropsPanel = rect;
        }
        saveConfiguration();
    }

    /** Records the current screen size as the one {@code rect}'s position was placed at. */
    private static void setRef(PanelRect rect) {
        Window window = Minecraft.getInstance().getWindow();
        rect.refWidth = window.getGuiScaledWidth();
        rect.refHeight = window.getGuiScaledHeight();
    }

    /**
     * Configs from before positions followed the screen: take the screen size the HUD is first drawn at as
     * the one the panels were placed at. Render thread only.
     */
    private void recordMissingRefs() {
        if (widgetRefWidth > 0 && craftablePanel.refWidth > 0 && forgingPanel.refWidth > 0 && totalPanel.refWidth > 0
            && mobDropsPanel.refWidth > 0) return;
        synchronized (this) {
            Window window = Minecraft.getInstance().getWindow();
            if (widgetRefWidth <= 0) {
                widgetRefWidth = window.getGuiScaledWidth();
                widgetRefHeight = window.getGuiScaledHeight();
            }
            // New copies rather than changing the saved ones in place (the update thread may be saving them).
            if (craftablePanel.refWidth <= 0) craftablePanel = withCurrentRef(craftablePanel);
            if (forgingPanel.refWidth <= 0) forgingPanel = withCurrentRef(forgingPanel);
            if (totalPanel.refWidth <= 0) totalPanel = withCurrentRef(totalPanel);
            if (mobDropsPanel.refWidth <= 0) mobDropsPanel = withCurrentRef(mobDropsPanel);
            saveConfiguration();
        }
    }

    private static PanelRect withCurrentRef(PanelRect r) {
        PanelRect copy = new PanelRect(r.x, r.y, r.width, r.height, r.scalePercent());
        setRef(copy);
        return copy;
    }

    /** Puts every panel back to its starting place (sizes and scales are kept): own panels go right of the main one. */
    public synchronized void resetPanelPositions() {
        widgetX = 10;
        widgetY = 40;
        int sideX = widgetX + Math.round(widgetWidth * scaleFactor(Panel.MAIN)) + 10;
        craftablePanel = new PanelRect(sideX, widgetY, craftablePanel.width, craftablePanel.height,
            craftablePanel.scalePercent());
        forgingPanel = new PanelRect(sideX, widgetY + Math.round(craftablePanel.height * scaleFactor(Panel.CRAFTABLE)) + 10,
            forgingPanel.width, forgingPanel.height, forgingPanel.scalePercent());
        totalPanel = new PanelRect(sideX, forgingPanel.y + Math.round(forgingPanel.height * scaleFactor(Panel.FORGING)) + 10,
            totalPanel.width, totalPanel.height, totalPanel.scalePercent());
        mobDropsPanel = new PanelRect(sideX, totalPanel.y + Math.round(totalPanel.height * scaleFactor(Panel.TOTAL)) + 10,
            mobDropsPanel.width, mobDropsPanel.height, mobDropsPanel.scalePercent());
        Window window = Minecraft.getInstance().getWindow();
        widgetRefWidth = window.getGuiScaledWidth();
        widgetRefHeight = window.getGuiScaledHeight();
        setRef(craftablePanel);
        setRef(forgingPanel);
        setRef(totalPanel);
        setRef(mobDropsPanel);
        saveConfiguration();
    }

    public static int minWidth(Panel panel) {
        return panel == Panel.MAIN ? 180 : 80;
    }

    public static int minHeight(Panel panel) {
        return panel == Panel.MAIN ? 120 : 40;
    }

    /** Panels the current style draws: the main one, plus Craftable / Forging / Total when they have their own. */
    public static List<Panel> activePanels() {
        HudStyle style = HudStyle.get();
        List<Panel> panels = new ArrayList<>();
        panels.add(Panel.MAIN);
        if (style.showCraftable && style.craftablePlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.CRAFTABLE);
        if (style.forgingSlots != HudStyle.ForgingSlots.OFF && style.forgingPlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.FORGING);
        if (getInstance().isShowTotal() && style.totalPlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.TOTAL);
        if (style.showMobDrops && style.mobDropsPlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.MOB_DROPS);
        return panels;
    }

    private void render(GuiGraphicsExtractor context) {
        recordMissingRefs();
        int inset = HudStyle.get().outerInset();
        List<Rect2i> drawn = new ArrayList<>(4);
        PanelPlacer current = placer;
        for (Panel panel : activePanels()) {
            Placement placed = current != null ? current.place(panel) : null;
            if (placed != null && placed.hidden()) continue;
            int x;
            int y;
            int width;
            int maxHeight;
            float scale;
            if (placed != null) {
                x = placed.x();
                y = placed.y();
                width = placed.width();
                maxHeight = placed.height();
                scale = placed.scale();
            } else {
                PanelRect rect = getPanelRect(panel);
                x = rect.x;
                y = rect.y;
                width = rect.width;
                maxHeight = rect.height;
                scale = scaleFactor(panel);
            }
            int height = renderPanel(context, panel, x, y, width, maxHeight, scale, false);
            if (height <= 0) continue;
            drawn.add(new Rect2i(x - inset, y - inset, Math.round(width * scale) + 2 * inset,
                Math.round(height * scale) + 2 * inset));
        }
        drawnPanels = drawn;
    }

    /** Where the HUD panels were last drawn (GUI pixels, borders included); empty while the HUD is hidden. */
    public List<Rect2i> getDrawnPanels() { return drawnPanels; }

    /**
     * Draws one panel with its top-left at (x, y) in GUI pixels; width and max height are HUD units. The panel is
     * as tall as its content up to the max height; anything below is cut off and marked with "…". The Move HUD
     * preview draws through here too, so it always matches. Returns the drawn height in HUD units (0 if nothing
     * was drawn). Render thread only.
     */
    public int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, boolean preview) {
        return renderPanel(context, panel, x, y, width, maxHeight, scaleFactor(panel), preview);
    }

    /** As above, at the given HUD units -> GUI pixels factor (Move HUD previews a scale before it is saved). */
    public int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, float scale,
                           boolean preview) {
        return renderPanel(context, panel, x, y, width, maxHeight, scale, preview, false);
    }

    /** Full height of a panel in HUD units if nothing were cut off (preview only, so empty panels count). */
    private int measurePanel(Panel panel, int width) {
        return renderPanel(null, panel, 0, 0, width, Integer.MAX_VALUE, 1f, true, true);
    }

    private int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, float scale,
                            boolean preview, boolean measureOnly) {
        HudStyle.checkFontOptions();
        HudStyle style = HudStyle.get();
        RecipeManager.RecipeNode root = previewing ? SAMPLE_TREE : this.recipeTree;
        List<Section> sections = sectionsFor(panel, style);
        String title;
        int rowsHeight = 0;
        // The Total, when it is drawn in its own panel rather than as the main panel's first row.
        RecipeManager.RecipeNode ownTotal = style.totalPlacement == HudStyle.Placement.OWN_PANEL ? totalNode(root) : null;
        switch (panel) {
            case MAIN -> {
                if (root == null) return 0;
                title = getTitle();
                int visibleLines = 0;
                for (RecipeManager.RecipeNode top : root.ingredients) {
                    if (top != ownTotal) visibleLines += countVisibleRecipeTreeLines(top, LIST_KEY, 0);
                }
                rowsHeight = visibleLines * (style.rowHeight + style.rowGap);
            }
            case TOTAL -> {
                title = I18n.get("skyblock-shopping-list.hud.total") + sacksReadTag();
                int visibleLines = 0;
                if (ownTotal != null) {
                    String key = makePathKey(LIST_KEY, ownTotal.name);
                    for (RecipeManager.RecipeNode child : ownTotal.ingredients) visibleLines += countVisibleRecipeTreeLines(child, key, 1);
                }
                if (visibleLines > 0) rowsHeight = visibleLines * (style.rowHeight + style.rowGap);
            }
            case CRAFTABLE -> title = I18n.get("skyblock-shopping-list.hud.craftable");
            case FORGING -> title = I18n.get("skyblock-shopping-list.hud.forging");
            default -> title = I18n.get("skyblock-shopping-list.hud.mob_drops");
        }
        boolean empty = panel != Panel.MAIN && rowsHeight == 0 && sections.stream().allMatch(s -> s.lines().isEmpty());
        if (empty && !preview) return 0;

        Minecraft client = Minecraft.getInstance();
        boolean ownPanel = panel != Panel.MAIN;
        int contentWidth = width - 2 * style.contentPadding();
        List<List<WrappedLine>> wrapped = new ArrayList<>();
        int sectionsHeight = 0;
        for (Section section : sections) {
            List<WrappedLine> lines = wrap(client, style, section, contentWidth);
            wrapped.add(lines);
            sectionsHeight += sectionHeight(section, lines.size(), ownPanel);
        }
        // With rounded corners wider than the padding, content stops above the bottom corners so it can't cover them;
        // a sprite frame keeps it above the bottom edge art.
        boolean drawnFrame = style.frame == HudStyle.Frame.DRAWN;
        int cornerClip = !drawnFrame ? style.frame.inset
            : Math.max(0, style.panelRadius - style.padding) > 0 ? style.panelRadius : 0;
        // Below the content: 4 px under rows, 2 under section lines only, room for the hint when empty.
        int bottom = empty ? 12 : panel == Panel.MAIN || rowsHeight > 0 ? 4 : 2;
        int naturalHeight = TITLE_BAR + style.titleGap + rowsHeight + sectionsHeight + bottom + cornerClip;
        int panelHeight = Math.min(maxHeight, naturalHeight);
        if (measureOnly) return panelHeight;

        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);

        // Its own stratum, so the panel isn't tested against everything vanilla and other mods drew before it.
        context.nextStratum();
        if (drawnFrame) {
            RoundedBox.fill(context, 0, 0, width, panelHeight, style.panelRadius, style.panelBackground);
            RoundedBox.fill(context, 0, 0, width, TITLE_BAR, style.panelRadius, true, false, style.headerBackground);
        } else {
            // Tooltip sprites have a 9 px empty margin (TooltipRenderUtil draws them 9 px out), so the visible
            // tooltip lands on the panel.
            int margin = style.frame == HudStyle.Frame.TOOLTIP ? 9 : 0;
            frameSprite(context, style, style.frame.sprite, -margin, -margin, width + 2 * margin, panelHeight + 2 * margin);
            if (style.frame.overlay != null) {
                frameSprite(context, style, style.frame.overlay, -margin, -margin, width + 2 * margin,
                    panelHeight + 2 * margin);
            }
        }
        HudStyle.Prepared titleText = style.prepared(title, style.titleText, true);
        int titleWidth = titleText.width();
        float titleScale = Math.min(style.titleScale, (float) (width - 10) / Math.max(1, titleWidth));
        drawScaled(context, titleText, alignedX(style.titleAlign, width, 5, titleWidth * titleScale),
            (TITLE_BAR - 8 * titleScale) / 2f, titleScale, style.textShadow);
        context.fill(style.frame.inset, TITLE_BAR - 1, width - style.frame.inset, TITLE_BAR, style.divider);

        // A sprite frame can't be covered with a fill when cut off, so the content stops above the "…" line instead.
        boolean cutOff = naturalHeight > panelHeight;
        context.enableScissor(0, TITLE_BAR, width, panelHeight - cornerClip - (cutOff && !drawnFrame ? 9 : 0));
        int cursorY = TITLE_BAR + style.titleGap;
        currentPanelWidth = width;
        currentNodeLineHeight = style.rowHeight;
        currentRowGap = style.rowGap;
        currentTreeScale = style.rowHeight / 16.0f;
        if (panel == Panel.MAIN) {
            for (RecipeManager.RecipeNode top : root.ingredients) {
                if (top != ownTotal) cursorY = renderRecipeTree(context, top, style.contentPadding(), cursorY, 0, LIST_KEY);
            }
        } else if (panel == Panel.TOTAL && ownTotal != null) {
            // Its rows are drawn like ingredients (level 1), but from the left edge with no line up to a parent.
            String key = makePathKey(LIST_KEY, ownTotal.name);
            currentBaseLevel = 1;
            try {
                for (RecipeManager.RecipeNode child : ownTotal.ingredients) {
                    cursorY = renderRecipeTree(context, child, style.contentPadding(), cursorY, 1, key);
                }
            } finally {
                currentBaseLevel = 0;
            }
        }
        if (empty) {
            HudStyle.Prepared hint = style.prepared(I18n.get("skyblock-shopping-list.hud.nothing_yet"), style.itemText, false);
            drawScaled(context, hint, (width - hint.width()) / 2f, cursorY + 2, 1f, style.textShadow);
        }
        for (int i = 0; i < sections.size(); i++) {
            cursorY = drawSection(context, sections.get(i), wrapped.get(i), width, cursorY, ownPanel);
        }
        context.disableScissor();

        if (cutOff) {
            // Cut off: tell the player there is more (raise the max height in Move HUD, or collapse rows).
            context.nextStratum();
            HudStyle.Prepared more = style.prepared("…", style.itemText, true);
            if (drawnFrame) {
                // At least twice the radius tall, or RoundedBox clamps the curve and the bottom corners change shape.
                int coverHeight = Math.max(9, style.panelRadius * 2);
                RoundedBox.fill(context, 0, panelHeight - coverHeight, width, coverHeight, style.panelRadius, false, true,
                    style.panelBackground);
            }
            int moreY = drawnFrame ? panelHeight - 9 : panelHeight - cornerClip - 9;
            drawScaled(context, more, (width - more.width()) / 2f, moreY, 1f, style.textShadow);
        }
        // The border goes on last, so dividers and the cut-off cover can't paint over its inner ring.
        context.nextStratum();
        for (int i = 0; drawnFrame && i < style.panelBorderWidth; i++) {
            // Outer border rings grow outward, so their radius grows with them to stay parallel.
            RoundedBox.outline(context, -i, -i, width + i * 2, panelHeight + i * 2,
                style.panelRadius > 0 ? style.panelRadius + i : 0, style.panelBorder);
        }
        context.pose().popMatrix();
        return panelHeight;
    }

    /** One frame sprite: from the chosen pack when it has one, else the atlas copy (the top pack's). */
    private static void frameSprite(GuiGraphicsExtractor context, HudStyle style, Identifier sprite,
                                    int x, int y, int w, int h) {
        if (style.framePack.isEmpty() || !PackFrames.draw(context, sprite, style.framePack, x, y, w, h)) {
            context.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
        }
    }

    /** The Total among the top rows (first, when shown), or null when the Total is off or empty. */
    private static RecipeManager.RecipeNode totalNode(RecipeManager.RecipeNode root) {
        if (root == null || root.ingredients == null || root.ingredients.isEmpty()) return null;
        RecipeManager.RecipeNode first = root.ingredients.get(0);
        return "Total".equals(first.name) ? first : null;
    }

    /** " (sacks 1/3 read)" while sacks the list needs haven't been opened yet, else empty. */
    private String sacksReadTag() {
        List<String> sacksLeft = neededSacks;
        if (sacksLeft.isEmpty()) return "";
        int listSackCount = listSacks.size();
        return I18n.get("skyblock-shopping-list.hud.sacks_read", listSackCount - sacksLeft.size(), listSackCount);
    }

    /** The Craftable / Forging blocks this panel shows. */
    private List<Section> sectionsFor(Panel panel, HudStyle style) {
        List<Section> sections = new ArrayList<>();
        boolean craftableHere = style.showCraftable
            && (style.craftablePlacement == HudStyle.Placement.OWN_PANEL ? panel == Panel.CRAFTABLE : panel == Panel.MAIN);
        boolean forgingHere = style.forgingSlots != HudStyle.ForgingSlots.OFF
            && (style.forgingPlacement == HudStyle.Placement.OWN_PANEL ? panel == Panel.FORGING : panel == Panel.MAIN);
        if (craftableHere) {
            // Built ready to draw, most first, by updateRecipeData: no per-frame work here.
            List<Line> craftable = previewing ? sampleCraftable() : messages;
            if (panel != Panel.MAIN || !craftable.isEmpty()) {
                sections.add(new Section(I18n.get("skyblock-shopping-list.hud.craftable"), craftable, style.craftableScale, style.craftableAlign,
                    style.sectionHeader, style.sectionText));
            }
        }
        if (forgingHere) {
            boolean all = style.forgingSlots == HudStyle.ForgingSlots.ALL;
            ForgingLines current = forgingLines;
            List<Line> forging = previewing ? (all ? sampleForging() : sampleForging().subList(0, 1))
                : all ? current.all() : current.listed();
            if (panel != Panel.MAIN || !forging.isEmpty()) {
                sections.add(new Section(I18n.get("skyblock-shopping-list.hud.forging"), forging, style.forgingScale, style.forgingAlign,
                    style.forgingHeader, style.forgingText, style.forgingOtherText));
            }
        }
        boolean mobDropsHere = style.showMobDrops
            && (style.mobDropsPlacement == HudStyle.Placement.OWN_PANEL ? panel == Panel.MOB_DROPS : panel == Panel.MAIN);
        if (mobDropsHere) {
            List<Line> drops = previewing ? sampleMobDrops() : mobDropLines;
            if (panel != Panel.MAIN || !drops.isEmpty()) {
                sections.add(new Section(I18n.get("skyblock-shopping-list.hud.mob_drops"), drops, style.mobDropsScale, style.mobDropsAlign,
                    style.mobDropsHeader, style.mobDropsText, style.mobDropsOverdueText));
            }
        }
        // The bill goes last, at the bottom of the main panel.
        if (style.showNpcPrice && panel == Panel.MAIN) {
            List<Line> prices = previewing ? sampleNpcPrice() : npcPriceHudLines;
            if (!prices.isEmpty()) {
                sections.add(new Section(I18n.get("skyblock-shopping-list.hud.npc_price"), prices, style.craftableScale, style.craftableAlign,
                    style.sectionHeader, style.sectionText));
            }
        }
        return sections;
    }

    /** Height of a section: divider and header inside the main panel (own panels have a title bar), then lines. */
    private static int sectionHeight(Section section, int lineCount, boolean ownPanel) {
        int header = ownPanel ? 0 : 6 + Math.round(13 * section.scale());
        return header + lineCount * Math.round(10 * section.scale()) + 2;
    }

    /**
     * Wrapped section lines by (lines, width, font, {@link HudStyle#fontGeneration()}); render thread only. The
     * lines rarely change between frames.
     */
    /**
     * Lines compared by value: the Move HUD preview makes its sample lines afresh each frame, so identity would
     * miss there. Line texts cache their hashes, so the lookup stays cheap.
     */
    private record WrapKey(List<Line> lines, int maxWidth, String font, int fontGeneration) {}

    /**
     * Render thread only. A handful of sections are on screen at once; past that the least recently used go
     * first, rather than every entry (which made the HUD and the Move HUD preview re-wrap each other's lines).
     */
    private static final Map<WrapKey, List<WrappedLine>> WRAP_CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<WrapKey, List<WrappedLine>> eldest) {
            return size() > 32;
        }
    };

    /** Splits each line into pieces that fit the content width at the section's scale. */
    private static List<WrappedLine> wrap(Minecraft client, HudStyle style, Section section, int contentWidth) {
        int maxWidth = Math.max(10, (int) Math.floor(contentWidth / Math.max(0.01f, section.scale())));
        // The lines are already immutable lists, so this is no copy.
        WrapKey key = new WrapKey(List.copyOf(section.lines()), maxWidth, style.font, HudStyle.fontGeneration());
        List<WrappedLine> cached = WRAP_CACHE.get(key);
        if (cached != null) return cached;
        List<WrappedLine> out = new ArrayList<>();
        for (Line message : section.lines()) {
            StringBuilder line = new StringBuilder();
            for (String word : message.text().split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && client.font.width(style.text(candidate, 0, false)) > maxWidth) {
                    out.add(new WrappedLine(line.toString(), message.tone()));
                    line = new StringBuilder("  ").append(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) out.add(new WrappedLine(line.toString(), message.tone()));
        }
        out = List.copyOf(out);
        WRAP_CACHE.put(key, out);
        return out;
    }

    private int drawSection(GuiGraphicsExtractor context, Section section, List<WrappedLine> lines, int width, int y,
                            boolean ownPanel) {
        Minecraft client = Minecraft.getInstance();
        HudStyle style = HudStyle.get();
        float scale = section.scale();
        context.nextStratum();
        if (!ownPanel) {
            context.fill(0, y, width, y + 1, style.divider);
            y += 5;
            HudStyle.Prepared header = style.prepared(section.header() + " -", section.headerColor(), true);
            drawScaled(context, header, alignedX(section.align(), width, style.contentPadding(), header.width() * scale),
                y, scale, style.textShadow);
            y += Math.round(13 * scale);
        }
        int lineHeight = Math.round(10 * scale);
        for (WrappedLine line : lines) {
            // By the whole line, so every wrapped piece of it gets the same colour.
            int color = switch (line.tone()) {
                case TEXT -> section.textColor();
                case OTHER -> section.otherColor();
                case DONE -> style.done;
            };
            HudStyle.Prepared text = style.prepared(line.text(), color, false);
            drawScaled(context, text, alignedX(section.align(), width, style.contentPadding(), text.width() * scale),
                y, scale, style.textShadow);
            y += lineHeight;
        }
        return y + 2;
    }

    /** Left edge for text of the given drawn width. */
    private static float alignedX(HudStyle.Align align, int width, int padding, float textWidth) {
        return switch (align) {
            case LEFT -> padding;
            case RIGHT -> width - padding - textWidth;
            case CENTRE -> (width - textWidth) / 2f;
        };
    }

    private int countVisibleRecipeTreeLines(RecipeManager.RecipeNode node, String pathKey, int level) {
        if (node == null || isHiddenRow(node, level)) return 0;
        int count = 1;
        // Path-based key keeps expansion stable regardless of amounts.
        String nodeKey = makePathKey(pathKey, node.name);
        if (node.ingredients != null && !node.ingredients.isEmpty() && isExpandedForDraw(nodeKey)) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                count += countVisibleRecipeTreeLines(child, nodeKey, level + 1);
            }
        }
        return count;
    }

    /**
     * Settings > Rows > Hide finished rows: a done ingredient row (and everything under it) isn't drawn. Top rows
     * always show, and so do rows whose count is uncertain (sack not read), so the warning isn't hidden.
     */
    private boolean isHiddenRow(RecipeManager.RecipeNode node, int level) {
        return level > 0 && HudStyle.get().hideFinished && node.amount <= 0 && node.toCraft <= 0 && !node.cooking
            && !isUncertain(node);
    }

    private boolean hasVisibleChildren(RecipeManager.RecipeNode node, int level) {
        if (node.ingredients == null) return false;
        for (RecipeManager.RecipeNode child : node.ingredients) {
            if (!isHiddenRow(child, level + 1)) return true;
        }
        return false;
    }

    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null || isHiddenRow(node, level)) return y;
        // Each row in its own stratum: vanilla places every element by testing it against all earlier ones in the
        // stratum, and rows don't overlap, so one stratum for the whole tree costs the square of its elements.
        context.nextStratum();
        HudStyle style = HudStyle.get();
        Minecraft client = Minecraft.getInstance();
        int unitIndent = Math.max(2, Math.round(style.indent * currentTreeScale));
        int indent = (level - currentBaseLevel) * unitIndent;
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0 && !node.cooking;
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = isExpandedForDraw(nodeKey);
        boolean hasChildren = hasVisibleChildren(node, level);
        boolean showRemaining = isShowRemaining();
        int nodeHeight = currentNodeLineHeight;
        int nodeWidth = Math.max(100, currentPanelWidth - 2 * style.contentPadding()) - indent;
        boolean uncertain = isUncertain(node);
        int statusColor = uncertain ? UNCERTAIN_COLOR : progressColor(node, showRemaining);

        if (style.showRowBoxes) {
            RoundedBox.fill(context, x + indent, y, nodeWidth, nodeHeight, style.rowRadius, style.rowBackground);
        }
        int barWidth = showsBar(node, level, pathKey) ? Math.round(nodeWidth * Math.clamp(node.progress, 0f, 1f)) : 0;
        if (barWidth > 0) {
            RoundedBox.fill(context, x + indent, y, barWidth, nodeHeight, Math.min(style.rowRadius, barWidth / 2),
                (statusColor & 0x00FFFFFF) | 0x55000000);
        }
        if (style.showRowBoxes) {
            int border = progressBorderColor(node, showRemaining);
            for (int i = 0; i < style.rowBorderWidth; i++) {
                RoundedBox.outline(context, x + indent + i, y + i, nodeWidth - 2 * i, nodeHeight - 2 * i,
                    Math.max(0, style.rowRadius - i), border);
            }
        }

        float rowScale = currentTreeScale * style.textScale;
        int iconOffset = hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale));
        if (hasChildren) {
            drawScaled(context, style.prepared(isExpanded ? "▼" : "▶", style.itemText, false),
                x + indent + Math.max(3, Math.round(5 * currentTreeScale)), textY(y, nodeHeight, rowScale), rowScale, style.textShadow);
        }

        int nameColor = level == 0 ? style.rootText : hasEnough ? style.itemText : statusColor;
        HudStyle.Prepared mark = style.showMarks
            ? style.prepared(hasEnough || (node.amount <= 0 && !node.cooking) ? "✔ " : "✖ ", statusColor, false)
            : HudStyle.Prepared.EMPTY;
        HudStyle.Prepared amount = style.prepared(amountText(node) + " ", statusColor, false);
        boolean bold = level == 0 && style.boldRootNames;
        HudStyle.Prepared name = style.prepared(level == 0 && "Total".equals(node.name)
            ? I18n.get("skyblock-shopping-list.hud.total") : node.name, nameColor, bold);
        String forge = forgeText(node, level);
        HudStyle.Prepared tag = forge.isEmpty() ? HudStyle.Prepared.EMPTY : style.prepared(forge, nameColor, false);
        int lockWidth = showsLock(node) ? LOCK_WIDTH : 0;
        int eventWidth = showsEvent(node) ? EVENT_WIDTH : 0;
        if (level == 0 && "Total".equals(node.name)) {
            // How far the sack scan for this list has got; gone once every needed sack has been opened.
            String sacks = sacksReadTag();
            if (!sacks.isEmpty()) tag = style.prepared(sacks, UNCERTAIN_COLOR, false);
        }
        int markWidth = mark.width();
        int amountWidth = amount.width();
        int tagWidth = tag.width();
        // The item's icon goes between the amount and the name, at the row's height (16 at most) whatever the text
        // scale; a row on a shown level without an icon keeps the gap, so the names line up.
        int iconSize = showsIcon(node, level) ? Math.min(nodeHeight, 16) : 0;
        int iconSpace = iconSize > 0 ? iconSize + 2 : 0;
        int maxTextWidth = Math.max(10, nodeWidth - iconOffset - iconSpace);
        float textScale;
        if (forge.isEmpty()) {
            int totalTextWidth = markWidth + amountWidth + name.width() + tagWidth + lockWidth + eventWidth;
            textScale = Math.min(rowScale, (float) maxTextWidth / Math.max(1, totalTextWidth));
        } else {
            // The forge time always shows; a long name is cut short to make room instead of shrinking the row.
            textScale = rowScale;
            int room = Math.round(maxTextWidth / rowScale) - markWidth - amountWidth - tagWidth - lockWidth - eventWidth;
            // Kept by the style, so a frame measures nothing new.
            name = style.fitted(node.name, nameColor, bold, room);
        }
        int nameWidth = name.width();
        int nameX = markWidth + amountWidth + Math.round(iconSpace / textScale);

        context.pose().pushMatrix();
        context.pose().translate(x + indent + iconOffset, textY(y, nodeHeight, textScale));
        context.pose().scale(textScale, textScale);
        context.text(client.font, mark.seq(), 0, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, amount.seq(), markWidth, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, name.seq(), nameX, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, tag.seq(), nameX + nameWidth, 0, 0xFFFFFFFF, style.textShadow);
        if (lockWidth > 0) drawLock(context, nameX + nameWidth + tagWidth, 0, nameColor);
        if (eventWidth > 0) drawEvent(context, nameX + nameWidth + tagWidth + lockWidth, 0);
        context.pose().popMatrix();
        if (iconSize > 0) {
            ItemStack icon = ItemIcons.icon(node.name);
            if (!icon.isEmpty()) {
                context.pose().pushMatrix();
                context.pose().translate(x + indent + iconOffset + (markWidth + amountWidth) * textScale,
                    y + (nodeHeight - iconSize) / 2f);
                context.pose().scale(iconSize / 16f, iconSize / 16f);
                context.fakeItem(icon, 0, 0);
                context.pose().popMatrix();
            }
        }
        y += nodeHeight + currentRowGap;

        if (hasChildren && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                if (isHiddenRow(child, level + 1)) continue;
                if (style.showTreeLines) {
                    int lineStartX = x + indent + Math.max(4, Math.round(6 * currentTreeScale));
                    int childIndentX = x + indent + unitIndent;
                    int vertLen = Math.max(3, nodeHeight / 2);
                    context.fill(lineStartX, y - currentRowGap, lineStartX + 1, y + vertLen, style.treeLines);
                    context.fill(lineStartX, y + vertLen, childIndentX, y + vertLen + 1, style.treeLines);
                }
                y = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
            }
        }
        return y;
    }

    /**
     * Settings > Rows > Progress bars / Total progress bars: whether this row gets a bar behind it. Levels count
     * from the top row of its tree (the Total row for the Total), also when the Total has its own panel.
     */
    private static boolean showsBar(RecipeManager.RecipeNode node, int level, String pathKey) {
        HudStyle style = HudStyle.get();
        return onLevel(isInTotal(node, level, pathKey) ? style.totalProgressBars : style.progressBars, level);
    }

    /** Settings > Rows > Item icons: whether this row gets its item's icon. Levels count as for the bars; never the Total row. */
    private static boolean showsIcon(RecipeManager.RecipeNode node, int level) {
        if (level == 0 && "Total".equals(node.name)) return false;
        return onLevel(HudStyle.get().itemIcons, level);
    }

    /** The Total row, or a row under it. */
    private static boolean isInTotal(RecipeManager.RecipeNode node, int level, String pathKey) {
        String totalKey = makePathKey(LIST_KEY, "Total");
        return level == 0 ? "Total".equals(node.name) : pathKey.equals(totalKey) || pathKey.startsWith(totalKey + ">");
    }

    private static boolean onLevel(HudStyle.ProgressBars mode, int level) {
        return switch (mode) {
            case OFF -> false;
            case TOP_LEVEL -> level == 0;
            case FIRST_LEVEL -> level <= 1;
            case ALL -> true;
        };
    }

    /** Top of text of the given scale, centred in a row. */
    private static float textY(int rowY, int rowHeight, float scale) {
        return rowY + Math.max(0, (rowHeight - 8 * scale) / 2f);
    }

    private static void drawScaled(GuiGraphicsExtractor context, HudStyle.Prepared text, float x, float y, float scale, boolean shadow) {
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);
        context.text(Minecraft.getInstance().font, text.seq(), 0, 0, 0xFFFFFFFF, shadow);
        context.pose().popMatrix();
    }

    /** False when the amount format shows the full required amounts (colours then skip "partly gathered"). */
    public boolean isShowRemaining() {
        return HudStyle.get().amountFormat != HudStyle.AmountFormat.REQUIRED;
    }
    public boolean isShowTotal() { return showTotal; }
    public void setShowTotal(boolean showTotal) {
        this.showTotal = showTotal;
        requestRefresh();
        saveConfiguration();
    }
    public int getTotalLevel() { return totalLevel; }
    public void setTotalLevel(int totalLevel) {
        this.totalLevel = clampTotalLevel(totalLevel);
        requestRefresh();
        saveConfiguration();
    }
    public Notifier.Channel getNotifyChannel(Notifier.Type type) {
        return notifyChannels.getOrDefault(type, type.defaultChannel);
    }
    public void setNotifyChannel(Notifier.Type type, Notifier.Channel channel) {
        notifyChannels.put(type, channel);
        saveConfiguration();
    }
    public boolean isAutoRemove() { return autoRemove; }
    public void setAutoRemove(boolean autoRemove) {
        this.autoRemove = autoRemove;
        requestRefresh();
        saveConfiguration();
    }
    public boolean isStockDeepestFirst() { return stockDeepestFirst; }
    public void setStockDeepestFirst(boolean stockDeepestFirst) {
        this.stockDeepestFirst = stockDeepestFirst;
        requestRefresh();
        saveConfiguration();
    }
    public boolean isItemListButton() { return itemListButton; }
    public void setItemListButton(boolean itemListButton) {
        this.itemListButton = itemListButton;
        saveConfiguration();
    }
    public boolean isDebugLogging() { return ShoppingList.debugLogging; }
    public void setDebugLogging(boolean debugLogging) {
        ShoppingList.debugLogging = debugLogging;
        saveConfiguration();
    }
    public int getMaxRecipes() { return maxRecipes; }
    public void setMaxRecipes(int maxRecipes) {
        this.maxRecipes = clampMaxRecipes(maxRecipes);
        saveConfiguration();
    }
    private static int clampMaxRecipes(int value) {
        return Math.max(1, Math.min(MAX_RECIPES_LIMIT, value));
    }

    /**
     * The amount shown next to a node, per Settings > Rows > Amount format: still to get ("3×"),
     * held of needed ("83/5,120"), or the full amount the recipe needs ("6×").
     */
    public static String amountText(RecipeManager.RecipeNode node) {
        boolean uncertain = isUncertain(node);
        return switch (HudStyle.get().amountFormat) {
            case REMAINING -> (uncertain ? "≤" : "") + number((long) node.amount + node.toCraft) + "×";
            case REQUIRED -> number(node.required) + "×";
            case HAVE_NEED -> (uncertain ? "≥" : "") + number(Math.max(0L, (long) node.required - node.amount - node.toCraft))
                + "/" + number(node.required);
        };
    }

    /**
     * True when the row is still missing items and its count depends on a sack not read yet: the player may
     * have more than shown.
     */
    public static boolean isUncertain(RecipeManager.RecipeNode node) {
        return node.uncertain;
    }

    /** Room a padlock takes after a row's text, in font pixels: a gap, the 5 px lock and a pixel after it. */
    public static final int LOCK_WIDTH = 9;
    /** Width of the "!" for a drop that can be farmed in a mayor's event now. */
    public static final int EVENT_WIDTH = 6;
    /** The "!": gold, like a mayor's perk in the election menu. */
    private static final int EVENT_COLOR = 0xFFFFAA00;

    /** Whether the row's item only drops during a mayor's event and that event is on now (Settings > Rows). */
    public static boolean showsEvent(RecipeManager.RecipeNode node) {
        return node.amount > 0 && MayorEvents.farmableNow(node.name) != null;
    }

    /** A 2x7 "!" drawn with fills, {@link #EVENT_WIDTH} wide, top-left at (x, y) of a text row. */
    public static void drawEvent(GuiGraphicsExtractor context, int x, int y) {
        int left = x + 2;
        context.fill(left, y, left + 2, y + 5, EVENT_COLOR);
        context.fill(left, y + 6, left + 2, y + 7, EVENT_COLOR);
    }

    /** True when a row shows a padlock: Recipe locks on, and it or a row under it still to make needs an unlock. */
    public static boolean showsLock(RecipeManager.RecipeNode node) {
        return HudStyle.get().showRequirements && (!node.ownLock.isEmpty() || !node.locksBelow.isEmpty());
    }

    /**
     * Fills {@link RecipeManager.RecipeNode#ownLock} and {@code locksBelow} for this row and every row under it,
     * so the HUD only reads them. Rows you have enough of need nothing made, so they pass nothing up. Returns
     * this subtree's requirements for the row above, each named after its item.
     */
    private static Set<String> setLocks(RecipeManager.RecipeNode node, RecipeManager recipes, int depth) {
        Set<String> below = new LinkedHashSet<>();
        if (node.ingredients != null && depth < RecipeManager.MAX_DEPTH) {
            for (RecipeManager.RecipeNode child : node.ingredients) below.addAll(setLocks(child, recipes, depth + 1));
        }
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0 && !node.cooking;
        if (hasEnough) {
            node.ownLock = "";
            node.locksBelow = List.of();
            return Set.of();
        }
        node.ownLock = recipes.getRequirement(node.name);
        node.locksBelow = List.copyOf(below);
        Set<String> up = new LinkedHashSet<>();
        if (!node.ownLock.isEmpty()) up.add(node.ownLock + " (" + node.name + ")");
        up.addAll(below);
        return up;
    }

    /**
     * A 5x7 padlock drawn with fills (Minecraft's fonts have no padlock glyph), {@link #LOCK_WIDTH} wide, top-left
     * at (x, y) on a text line.
     */
    public static void drawLock(GuiGraphicsExtractor context, int x, int y, int color) {
        int left = x + 3;
        context.fill(left + 1, y, left + 4, y + 1, color);          // shackle top
        context.fill(left, y + 1, left + 1, y + 3, color);          // shackle sides
        context.fill(left + 4, y + 1, left + 5, y + 3, color);
        context.fill(left, y + 3, left + 5, y + 7, color);          // body
        context.fill(left + 2, y + 4, left + 3, y + 6, 0xFF000000 | (~color & 0xFFFFFF)); // keyhole
    }

    /**
     * Forge time still ahead for a row, per Settings > Rows > Forge times: "[25hrs]", "[45m]" or
     * "[<1m]", rounded up so it never shows less than is left; empty when there is none or the setting hides it for this row (level 0 = the top rows).
     */
    public static String forgeText(RecipeManager.RecipeNode node, int level) {
        HudStyle.ForgeTimes mode = HudStyle.get().forgeTimes;
        if (mode == HudStyle.ForgeTimes.OFF || (mode == HudStyle.ForgeTimes.TOP_LEVEL && level > 0)) return "";
        long left = node.forgeLeft(System.currentTimeMillis());
        if (left <= 0) return "";
        if (left < 60_000) return I18n.get("skyblock-shopping-list.hud.forge_time.under_minute");
        long minutes = (left + 59_999) / 60_000;
        if (minutes < 60) return I18n.get("skyblock-shopping-list.hud.forge_time.minutes", minutes);
        return I18n.get("skyblock-shopping-list.hud.forge_time.hours", (minutes + 59) / 60);
    }

    /**
     * {@code name} followed by a space if it fits in {@code room} font pixels, otherwise cut short and ended
     * with "..." (so a forge time drawn straight after reads "Mithril Dri...[25hrs]").
     */
    static Component fitName(Font font, HudStyle style, String name, int color, boolean bold, int room) {
        return fitName(font, text -> style.text(text, color, bold), name, room);
    }

    /** As above, with {@code make} turning text into a styled component (screens use their own style). */
    static Component fitName(Font font, java.util.function.Function<String, Component> make, String name, int room) {
        return fitName(name, room, make, font::width);
    }

    /** As above, for any text form: {@code make} styles the text and {@code width} measures it. */
    static <T> T fitName(String name, int room, java.util.function.Function<String, T> make,
                                 java.util.function.ToIntFunction<T> width) {
        T full = make.apply(name + " ");
        if (width.applyAsInt(full) <= room) return full;
        // Longest start of the name that still fits with "..." (binary search: runs every frame per row).
        int lo = 0;
        int hi = name.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width.applyAsInt(make.apply(name.substring(0, mid).stripTrailing() + "...")) <= room) lo = mid;
            else hi = mid - 1;
        }
        return make.apply(name.substring(0, lo).stripTrailing() + "...");
    }

    /** An amount as the style wants it: "512", "5,120", or with Short numbers on "5.1k", "500m", "1.5b". */
    public static String number(long value) {
        if (!HudStyle.get().shortNumbers) return String.format(Locale.ROOT, "%,d", value);
        return Counts.shortNumber(value);
    }

    /**
     * Green when held; blue when held only by counting copies still cooking in the Forge; yellow when the rest
     * can be crafted from materials you have; with remaining mode on,
     * orange when partly gathered and red when none yet.
     */
    public static int progressColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        HudStyle style = HudStyle.get();
        if (node.amount <= 0) return node.toCraft > 0 ? style.craftable : node.cooking ? style.cooking : style.done;
        if (showRemaining && node.amount < node.required) return style.partial;
        return style.missing;
    }

    /** Translucent version of {@link #progressColor} for node borders. */
    public static int progressBorderColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        return (progressColor(node, showRemaining) & 0x00FFFFFF) | 0x88000000;
    }

    public static String makePathKey(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty()) return parent;
        return parent + ">" + name;
    }
}

