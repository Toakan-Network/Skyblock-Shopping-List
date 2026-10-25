package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named shopping lists the player saved (List tab > Saved lists), e.g. "Mining" and "Foraging", to swap the
 * current list for. Kept in saved_lists.json for every profile; not part of the data reset, like HUD presets.
 * A saved list can be shared as a code ({@link #CODE_PREFIX}, see {@link ShareCodes}) holding its name and
 * entries, so players can pass build paths around on Discord or in guides.
 */
public final class SavedLists {
    public static final int NAME_LIMIT = 32;
    /** Differs from the HUD preset prefix, so neither kind of code is read as the other. */
    public static final String CODE_PREFIX = "SSLLIST1:";
    /** A list holds far fewer recipes; anything bigger is refused. */
    private static final int MAX_CODE_ENTRIES = 200;
    private static final int MAX_CODE_CHARS = 32 * 1024;
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private static final Gson CODE_GSON = new Gson();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, List<ShoppingListEntry>>>() {}.getType();
    /** Name to entries, in the order they were first saved. */
    private static LinkedHashMap<String, List<ShoppingListEntry>> lists;

    private SavedLists() {}

    private static LinkedHashMap<String, List<ShoppingListEntry>> lists() {
        if (lists == null) {
            LinkedHashMap<String, List<ShoppingListEntry>> saved = JsonFiles.read(FilePathManager.SAVED_LISTS_JSON, TYPE);
            lists = new LinkedHashMap<>();
            if (saved != null) {
                saved.forEach((name, entries) -> {
                    if (name != null && !name.isBlank() && entries != null) lists.put(name, clean(entries));
                });
            }
        }
        return lists;
    }

    public static synchronized List<String> names() {
        return new ArrayList<>(lists().keySet());
    }

    /** The saved entries (copies), or null if there is no list by that name. */
    public static synchronized List<ShoppingListEntry> get(String name) {
        List<ShoppingListEntry> entries = lists().get(name);
        return entries == null ? null : clean(entries);
    }

    /** Saves {@code entries} as {@code name}, replacing a list already saved under it. */
    public static synchronized void save(String name, List<ShoppingListEntry> entries) {
        lists().put(name, clean(entries));
        write();
    }

    public static synchronized void delete(String name) {
        if (lists().remove(name) != null) write();
    }

    /** What a pasted code added: the saved name, recipes kept, and recipes left out as unknown. */
    public record Import(String name, int added, int unknown) {}

    /** A share code for the saved list {@code name}, or null if there is none. */
    public static synchronized String code(String name) {
        List<ShoppingListEntry> entries = lists().get(name);
        if (entries == null) return null;
        Shared shared = new Shared();
        shared.name = name;
        shared.entries = new ArrayList<>();
        for (ShoppingListEntry e : entries) shared.entries.add(new SharedEntry(e.recipe, e.amount, e.isHaveTotal()));
        return ShareCodes.encode(CODE_PREFIX, CODE_GSON.toJson(shared));
    }

    /**
     * Saves the list in a share code under a free name (" (2)" added when taken); the current list is left alone.
     * Recipes this mod doesn't know are left out. Returns null, with nothing saved, when {@code code} isn't a list
     * code or holds no known recipe.
     */
    public static synchronized Import importCode(String code) {
        String json = ShareCodes.decode(CODE_PREFIX, code, MAX_CODE_CHARS, MAX_JSON_BYTES);
        if (json == null) return null;
        Shared shared;
        try {
            shared = CODE_GSON.fromJson(json, Shared.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (shared == null || shared.entries == null) return null;
        Set<String> known = new HashSet<>(RecipeManager.getInstance().getRecipeNames());
        List<ShoppingListEntry> entries = new ArrayList<>();
        int unknown = 0;
        for (SharedEntry e : shared.entries) {
            if (e == null || e.recipe == null || entries.size() >= MAX_CODE_ENTRIES) continue;
            if (!known.contains(e.recipe)) {
                unknown++;
                continue;
            }
            if (entries.stream().anyMatch(x -> x.recipe.equals(e.recipe))) continue;
            entries.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), 0, e.haveTotal == null || e.haveTotal));
        }
        if (entries.isEmpty()) return null;
        String name = uniqueName(shared.name);
        save(name, entries);
        return new Import(name, entries.size(), unknown);
    }

    /** {@code name} cleaned and cut to {@link #NAME_LIMIT}, with " (2)", " (3)"... added when a list has it. */
    private static String uniqueName(String name) {
        String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").trim();
        if (clean.isEmpty()) clean = "Imported";
        String unique = clean.length() > NAME_LIMIT ? clean.substring(0, NAME_LIMIT).trim() : clean;
        for (int n = 2; lists().containsKey(unique); n++) {
            String suffix = " (" + n + ")";
            unique = (clean.length() + suffix.length() > NAME_LIMIT
                ? clean.substring(0, NAME_LIMIT - suffix.length()).trim() : clean) + suffix;
        }
        return unique;
    }

    /** A saved list as it travels in a share code; no start counts, those belong to the sharer's profile. */
    private static final class Shared {
        String name;
        List<SharedEntry> entries;
    }

    private static final class SharedEntry {
        String recipe;
        int amount;
        Boolean haveTotal;

        SharedEntry(String recipe, int amount, boolean haveTotal) {
            this.recipe = recipe;
            this.amount = amount;
            this.haveTotal = haveTotal;
        }
    }

    private static void write() {
        Map<String, List<ShoppingListEntry>> copy = new LinkedHashMap<>(lists);
        JsonFiles.writeAsync(FilePathManager.SAVED_LISTS_JSON, () -> copy);
    }

    /**
     * Valid entries only, copied. The starting count belongs to the profile that was playing when it was saved,
     * so it is dropped; loading takes it afresh.
     */
    private static List<ShoppingListEntry> clean(List<ShoppingListEntry> entries) {
        List<ShoppingListEntry> out = new ArrayList<>();
        for (ShoppingListEntry e : entries) {
            if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                out.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), 0, e.isHaveTotal()));
            }
        }
        return out;
    }
}
