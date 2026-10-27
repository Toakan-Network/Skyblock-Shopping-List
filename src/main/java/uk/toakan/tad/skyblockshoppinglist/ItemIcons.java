package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Item icons for the HUD rows, by recipe name. Written by the recipe fetch to item_icons.json as SNBT item stacks
 * (look only); read the first time an icon is needed, not at start-up. Render thread only.
 */
public final class ItemIcons {
    private static final Type TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static volatile Map<String, String> snbt;
    /** Stacks made so far; EMPTY for names without an icon, so they aren't parsed again every frame. */
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private ItemIcons() {}

    /** Forgets the loaded file, so the next icon reads the new one (after the recipe fetch rewrote it). */
    public static void reload() {
        snbt = null;
        Minecraft.getInstance().execute(STACKS::clear);
    }

    private static Map<String, String> snbt() {
        Map<String, String> current = snbt;
        if (current == null) {
            Map<String, String> loaded = JsonFiles.read(FilePathManager.ITEM_ICONS_JSON, TYPE);
            current = loaded != null ? loaded : Map.of();
            snbt = current;
        }
        return current;
    }

    /** The item's icon, or {@link ItemStack#EMPTY} when there is none (or no world to read the stack against). */
    public static ItemStack icon(String name) {
        ItemStack cached = STACKS.get(name);
        if (cached != null) return cached;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return ItemStack.EMPTY;
        ItemStack stack = parse(snbt().get(name), client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE));
        STACKS.put(name, stack);
        return stack;
    }

    /** The stack, or just its item when a component doesn't read (repo data from an older game version). */
    private static ItemStack parse(String text, RegistryOps<net.minecraft.nbt.Tag> ops) {
        if (text == null) return ItemStack.EMPTY;
        try {
            CompoundTag tag = TagParser.parseCompoundFully(text);
            ItemStack stack = ItemStack.CODEC.parse(ops, tag).result().orElse(null);
            if (stack == null) {
                tag.remove("components");
                stack = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
            }
            return stack;
        } catch (Exception e) {
            ShoppingList.debug("Unreadable icon {}: {}", text, e.toString());
            return ItemStack.EMPTY;
        }
    }
}
