package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class ShoppingListClient implements ClientModInitializer {
    private static final String INVENTORY_KEY = "Player Inventory";
    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

    /** Own section in Options > Controls > Key Binds; label from key.category.skyblock-shopping-list.main. */
    private static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ShoppingList.MOD_ID, "main"));
    private static KeyMapping openMenuKey;
    private static KeyMapping moveHudKey;
    private static KeyMapping toggleWidgetKey;
    /** Set by commands; the screen opens on the next tick, after the chat screen has closed. */
    public static boolean shouldOpenMenu = false;
    public static boolean shouldOpenMoveHud = false;

    /** Last inventory contents written to inventorydata.json; null until loaded. */
    private static Map<String, Integer> lastInventory;
    /**
     * Each slot's stack and count at the last check, and the item name table then. When none changed there is
     * nothing to count: the scan runs every 2 ticks and nearly always finds the inventory as it was.
     */
    private static ItemStack[] lastStacks = new ItemStack[0];
    private static int[] lastCounts = new int[0];
    private static long lastNamesVersion = -1;
    private int tickCounter = 0;

    private static final java.util.Map<String, Integer> INPUT_CODES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Reads an InputConstants code at runtime. The codes differ between Minecraft versions (26.3 moved
     * from GLFW to SDL), and javac would inline a direct reference, so one jar works on both this way.
     */
    public static int inputCode(String name) {
        // Looked up once per name: callers ask on every click.
        return INPUT_CODES.computeIfAbsent(name, n -> {
            try {
                return InputConstants.class.getField(n).getInt(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Missing InputConstants." + n, e);
            }
        });
    }

    @Override
    public void onInitializeClient() {
        FilePathManager.initialize();

        // Key ids are kept from earlier versions so existing bindings in options.txt carry over.
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_sandbox_viewer", inputCode("KEY_V"), KEY_CATEGORY));
        moveHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_widget_customization", inputCode("KEY_B"), KEY_CATEGORY));
        toggleWidgetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.toggle_widget", inputCode("KEY_H"), KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Keys only act on SkyBlock (presses are still consumed so they don't queue up);
            // the /ssl commands work anywhere.
            boolean onSkyblock = SkyblockDetector.isOnSkyblock();
            if ((openMenuKey.consumeClick() && onSkyblock) || shouldOpenMenu) {
                shouldOpenMenu = false;
                MenuTabs.open(MenuTabs.Tab.SHOPPING_LIST);
            }
            if ((moveHudKey.consumeClick() && onSkyblock) || shouldOpenMoveHud) {
                shouldOpenMoveHud = false;
                SandboxWidget.openMoveHud(null);
            }
            if (toggleWidgetKey.consumeClick() && onSkyblock) {
                SandboxWidget widget = SandboxWidget.getInstance();
                widget.setEnabled(!widget.isEnabled());
            }
            // Other servers' inventories must never change the SkyBlock counts.
            // Waits for the profile after a server change, so its items aren't booked to the last one.
            if (SkyblockDetector.isTracking() && client.player != null && client.level != null && ++tickCounter >= 2) {
                tickCounter = 0;
                checkInventory(client);
            }
        });

        SkyblockDetector.register();
        StorageViewerMod.register();
        SslCommandManager.register();
        SackChatListener.register();
        ProfileManager.register();
        ProfileChatListener.register();
        // Saves run on a background thread; make sure the last ones reach the disk before the game closes.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> JsonFiles.flush());
        // After the fonts reload (resource packs, F3+T), the HUD's cached text widths are stale.
        Identifier fontsReloaded = Identifier.fromNamespaceAndPath(ShoppingList.MOD_ID, "hud_fonts");
        ResourceLoader resources = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resources.registerReloadListener(fontsReloaded,
            (ResourceManagerReloadListener) manager -> HudStyle.fontsChanged());
        resources.addListenerOrdering(ResourceReloaderKeys.Client.FONTS, fontsReloaded);
        CoinTracker.register();
        PowderTracker.register();
        KillTracker.register();
        MayorEvents.register();
        ReminderManager.initialize();
        WelcomeManager.initialize();
        SandboxWidget.getInstance();
        HudPresets.migrate();
        ShoppingList.debug("Initialized {} client components", ShoppingList.NAME);
    }

    /** The keys as bound now, e.g. "[V]", for chat text. */
    static String openMenuKeyName() { return keyName(openMenuKey); }
    static String moveHudKeyName() { return keyName(moveHudKey); }
    static String toggleWidgetKeyName() { return keyName(toggleWidgetKey); }

    private static String keyName(KeyMapping key) {
        return "[" + key.getTranslatedKeyMessage().getString() + "]";
    }

    /** Forgets the remembered inventory (after a reset deleted the file). */
    public static void clearInventorySnapshot() {
        lastInventory = null;
        lastStacks = new ItemStack[0];
    }

    /** The inventory as last counted (what its changes so far have been passed on against). */
    static Map<String, Integer> inventoryCounts() {
        return new HashMap<>(loadedInventory());
    }

    private static Map<String, Integer> loadedInventory() {
        if (lastInventory == null) {
            Map<String, Map<String, Integer>> saved = JsonFiles.read(FilePathManager.inventoryJson(), DATA_TYPE);
            Map<String, Integer> previous = saved != null ? saved.get(INVENTORY_KEY) : null;
            lastInventory = previous != null ? previous : new HashMap<>();
        }
        return lastInventory;
    }

    /**
     * True when every slot holds the same stack object with the same count as at the last check (Hypixel sends a new
     * stack when an item's data changes) and the item names are the same; otherwise remembers the slots for next time.
     */
    private static boolean unchanged(Inventory inventory) {
        int size = inventory.getContainerSize();
        long names = ItemIds.getVersion();
        boolean same = lastStacks.length == size && names == lastNamesVersion;
        for (int i = 0; same && i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            same = stack == lastStacks[i] && stack.getCount() == lastCounts[i];
        }
        if (same) return true;
        if (lastStacks.length != size) {
            lastStacks = new ItemStack[size];
            lastCounts = new int[size];
        }
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            lastStacks[i] = stack;
            lastCounts[i] = stack.getCount();
        }
        lastNamesVersion = names;
        return false;
    }

    private static void checkInventory(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        if (unchanged(inventory)) return;
        Map<String, Integer> current = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                ItemIds.count(stack, current);
            }
        }

        if (current.equals(loadedInventory())) return;

        Map<String, Integer> changes = Counts.diff(lastInventory, current);

        lastInventory = current;
        Map<String, Map<String, Integer>> data = new HashMap<>();
        data.put(INVENTORY_KEY, current);
        JsonFiles.writeAsync(FilePathManager.inventoryJson(), () -> data);
        ResourcesManager.getInstance().saveData(changes);
        // Worn armor also shows in the wardrobe; its count there depends on what is worn.
        StorageReader.getInstance().refreshWorn();
    }
}
