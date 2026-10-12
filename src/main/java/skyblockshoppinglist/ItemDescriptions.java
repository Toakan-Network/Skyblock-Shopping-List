package skyblockshoppinglist;

import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Item descriptions (the NEU lore lines, colour codes kept) for the menu's hover tooltips. Written by the recipe
 * fetch to item_lore.json; read the first time a tooltip is needed, not at start-up.
 */
public final class ItemDescriptions {
    private static final Type TYPE = new TypeToken<Map<String, List<String>>>() {}.getType();
    private static volatile Map<String, List<String>> lore;

    private ItemDescriptions() {}

    /** Forgets the loaded file, so the next tooltip reads the new one (after the recipe fetch rewrote it). */
    public static void reload() {
        lore = null;
    }

    private static Map<String, List<String>> lore() {
        Map<String, List<String>> current = lore;
        if (current == null) {
            Map<String, List<String>> loaded = JsonFiles.read(FilePathManager.ITEM_LORE_JSON, TYPE);
            current = loaded != null ? loaded : Map.of();
            lore = current;
        }
        return current;
    }

    /** The item's name and description as tooltip lines, or an empty list when there is no description for it. */
    public static List<Component> tooltip(String name) {
        if (name == null) return List.of();
        List<String> lines = lore().get(name);
        if (lines == null || lines.isEmpty()) return List.of();
        List<Component> out = new ArrayList<>(lines.size() + 1);
        out.add(Component.literal(name).withStyle(ChatFormatting.WHITE));
        // The lines keep Hypixel's § colour codes, which the font draws as colours.
        for (String line : lines) out.add(Component.literal(line));
        return out;
    }
}
