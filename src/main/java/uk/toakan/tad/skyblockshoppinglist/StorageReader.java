package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers the contents of each storage container (backpacks, ender chest pages, forge, accessory bag, Pets menu pages,
 * wardrobe pages, worn equipment) by title, and passes changes since the last time it was seen to {@link ResourcesManager}.
 */
public class StorageReader {
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static final StorageReader INSTANCE = new StorageReader();
    /** The Pets menu, one title per page: "Pets" or "(1/3) Pets". */
    private static final Pattern PETS_MENU = Pattern.compile("^(\\(\\d+/\\d+\\) )?Pets$");
    /** Wardrobe pages: "Armor Sets" or "(2/3) Equipment Sets" (the titles Skyblocker matches). */
    private static final Pattern WARDROBE = Pattern.compile("^(\\(\\d+/\\d+\\) )?(Armor Sets|Equipment Sets)$");
    /** The menu showing worn equipment (necklace, cloak, belt, gloves). */
    private static final String EQUIPMENT_MENU = "Stats & Equipment";
    /**
     * Not a menu: the armor worn when the wearable menus were last counted. Those menus are stored as shown (worn items
     * included) and the worn copies are taken off when counting, so swapping armor needs no re-read of the wardrobe.
     */
    private static final String WORN_KEY = "#Worn armor";

    private Map<String, Map<String, Integer>> containers;

    private StorageReader() {}

    public static StorageReader getInstance() {
        return INSTANCE;
    }

    public static boolean isTrackedContainer(String title) {
        return title.contains("Backpack") || title.contains("Ender Chest")
            || title.contains("The Forge") || title.contains("Accessory Bag") || isPetsMenu(title)
            || isWearableMenu(title);
    }

    /** Wardrobe pages and the worn-equipment menu. */
    private static boolean isWearableMenu(String title) {
        return WARDROBE.matcher(title).matches() || title.startsWith(EQUIPMENT_MENU);
    }

    private static boolean isPetsMenu(String title) {
        return PETS_MENU.matcher(title).matches();
    }

    private Map<String, Map<String, Integer>> containers() {
        if (containers == null) {
            Map<String, Map<String, Integer>> loaded = JsonFiles.read(FilePathManager.containerJson(), TYPE);
            containers = loaded != null ? new HashMap<>(loaded) : new HashMap<>();
        }
        return containers;
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public synchronized void clear() {
        containers = null;
    }

    public synchronized void saveContainerContents(AbstractContainerMenu handler, String title) {
        if (!isTrackedContainer(title)) return;

        Map<String, Integer> newData = new LinkedHashMap<>();
        // The Pets menu also holds buttons (sort, convert, close...); only the pets count.
        boolean petsOnly = isPetsMenu(title);
        // These menus mix items with buttons; only real SkyBlock items count.
        boolean wearable = isWearableMenu(title);
        boolean itemsOnly = wearable || title.contains("Accessory Bag");
        // The player's own inventory (below the menu) is tracked separately.
        for (ItemStack stack : MenuSlots.containerStacks(handler)) {
            if (petsOnly ? !ItemIds.isPet(stack) : itemsOnly && !ItemIds.hasSkyblockId(stack)) continue;
            if (wearable && ItemIds.isPet(stack)) continue;
            ItemIds.count(stack, newData);
        }

        Map<String, Integer> previous = containers().getOrDefault(title, Map.of());
        if (previous.equals(newData)) return;
        if (wearable) {
            updateWearable(title, newData, armorCounts());
            return;
        }

        Map<String, Integer> changes = Counts.diff(previous, newData);

        containers.put(title, newData);
        Map<String, Map<String, Integer>> copy = new HashMap<>(containers);
        JsonFiles.writeAsync(FilePathManager.containerJson(), () -> copy);
        ResourcesManager.getInstance().saveData(changes);
    }

    /** Recounts the wearable menus after the worn armor changed (called when the inventory changes). */
    public synchronized void refreshWorn() {
        Map<String, Integer> armor = armorCounts();
        if (armor.equals(containers().get(WORN_KEY))) return;
        updateWearable(null, null, armor);
    }

    /** Stores a wearable menu's contents (if given) and the worn armor, and reports how the counted items changed. */
    private void updateWearable(String title, Map<String, Integer> contents, Map<String, Integer> armor) {
        Map<String, Integer> before = wearableCounts();
        if (title != null) containers.put(title, contents);
        containers.put(WORN_KEY, armor);
        Map<String, Integer> changes = Counts.diff(before, wearableCounts());

        Map<String, Map<String, Integer>> copy = new HashMap<>(containers);
        JsonFiles.writeAsync(FilePathManager.containerJson(), () -> copy);
        if (!changes.isEmpty()) ResourcesManager.getInstance().saveData(changes);
    }

    /**
     * What the wearable menus add to the counts: their items minus the worn copies, which are counted elsewhere (armor
     * with the inventory, equipment from the equipment menu). A spare copy of a worn item stored there still counts.
     */
    private Map<String, Integer> wearableCounts() {
        Map<String, Integer> armor = containers().get(WORN_KEY);
        Map<String, Integer> equipment = new HashMap<>();
        Map<String, Integer> wardrobe = new HashMap<>();
        containers.forEach((key, items) -> {
            if (key.startsWith(EQUIPMENT_MENU)) items.forEach((name, count) -> equipment.merge(name, count, Integer::sum));
            else if (WARDROBE.matcher(key).matches()) items.forEach((name, count) -> wardrobe.merge(name, count, Integer::sum));
        });
        // Files from before WORN_KEY hold these menus with the worn copies already taken off.
        if (armor == null) {
            equipment.forEach((name, count) -> wardrobe.merge(name, count, Integer::sum));
            return wardrobe;
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> worn = new HashMap<>(armor);
        equipment.forEach((name, count) -> {
            int left = count - armor.getOrDefault(name, 0);
            if (left > 0) {
                counts.merge(name, left, Integer::sum);
                worn.merge(name, left, Integer::sum);
            }
        });
        wardrobe.forEach((name, count) -> {
            int left = count - worn.getOrDefault(name, 0);
            if (left > 0) counts.merge(name, left, Integer::sum);
        });
        return counts;
    }

    /**
     * What every container adds to the item counts, by display name: the same totals {@link #saveContainerContents}
     * has passed on change by change.
     */
    public synchronized Map<String, Integer> counts() {
        Map<String, Integer> counts = new HashMap<>(wearableCounts());
        containers().forEach((key, items) -> {
            if (key.equals(WORN_KEY) || isWearableMenu(key)) return;
            items.forEach((name, count) -> counts.merge(name, count, Integer::sum));
        });
        return counts;
    }

    /** How many of each item the player wears as armor, counted like the inventory and menus (gems and parts included). */
    private static Map<String, Integer> armorCounts() {
        Map<String, Integer> counts = new HashMap<>();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Inventory inventory = client.player.getInventory();
            for (int i = Inventory.INVENTORY_SIZE; i < Inventory.SLOT_OFFHAND; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) ItemIds.count(stack, counts);
            }
        }
        return counts;
    }
}
