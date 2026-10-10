package inventoryreader.ir.recipes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import inventoryreader.ir.FilePathManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skyblockdb.api.Attribute;
import skyblockdb.api.Recipe;
import skyblockdb.api.ReferenceItem;
import skyblockdb.api.SkyblockDbApi;
import skyblockdb.api.SkyblockDbListener;

import java.io.File;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the recipe files from SkyblockDB's reference data (items, recipes, unlock requirements, Hunting attributes
 * and NPC sell prices, which it keeps from SkyblockAPI's copy of the NotEnoughUpdates repository and Hypixel's item
 * list). Runs whenever SkyblockDB's reference data changes. Only NEU's sack list is fetched here, as SkyblockDB
 * doesn't carry it.
 */
public final class RemoteRecipeFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("IR-RemoteRecipeFetcher");
    private static final Gson GSON = new Gson();
    /** Which sack holds which item; the one file SkyblockDB doesn't have. Small, cached with an ETag. */
    private static final String SACKS_URL =
        "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/sacks.json";
    /** Coin costs are keyed like NEU's pseudo item, so names and the Coins resource stay as before. */
    private static final String COIN_ID = "SKYBLOCK_COIN";
    public static final String COINS_NAME = "Coins";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean AGAIN = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IR-RecipeFetch");
        t.setDaemon(true);
        return t;
    });

    private RemoteRecipeFetcher() {}

    /** Builds the recipes whenever SkyblockDB's reference data changes, and now if it is already there. */
    public static void register() {
        inventoryreader.ir.SkyblockDbHook.whenReady(db -> {
            db.addListener(new SkyblockDbListener() {
                @Override
                public void onReferenceDataChanged(Set<String> kinds, Set<String> ids) {
                    fetchAsync();
                }
            });
            if (!db.referenceItems().isEmpty()) fetchAsync();
        });
    }

    /** Starts a background build; one asked for while another runs follows it. */
    public static void fetchAsync() {
        if (!RUNNING.compareAndSet(false, true)) {
            AGAIN.set(true);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                do {
                    AGAIN.set(false);
                    build();
                } while (AGAIN.get());
            } catch (Throwable t) {
                LOGGER.warn("Recipe build failed: {}", t.toString());
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void build() throws Exception {
        SkyblockDbApi db = SkyblockDbApi.get().orElse(null);
        if (db == null) return;
        List<Recipe> recipes = db.allRecipes();
        if (recipes.stream().noneMatch(r -> r.type().equals("crafting"))) {
            LOGGER.warn("SkyblockDB has no recipes yet; keeping the current files");
            return;
        }
        Map<String, String> meta = readMeta(FilePathManager.REMOTE_META_JSON);

        Map<String, String> internalToDisplay = new LinkedHashMap<>();
        Map<String, String> typeByInternal = new LinkedHashMap<>();
        // In ID order, so which of two same-named items gets the plain name never changes between updates.
        Map<String, ReferenceItem> items = new java.util.TreeMap<>(db.referenceItems());
        for (ReferenceItem item : items.values()) {
            String display = stripMC(item.name());
            if (display != null && !display.isBlank()) internalToDisplay.putIfAbsent(item.sbId(), display);
            // Rarity and type ("LEGENDARY|ACCESSORY"), for the List tab filters. Pets have their own rarity tiers.
            if (!item.rarity().isEmpty() && !"PET".equals(item.category())) {
                typeByInternal.putIfAbsent(item.sbId(), item.rarity() + "|" + item.category());
            }
        }
        internalToDisplay.put(COIN_ID, COINS_NAME);
        // Attribute inputs stand for their Hunting shard ("HONEY_REFILL" -> Queen Bee Shard, SHARD_QUEEN_BEE).
        Map<String, String> attributeByShard = new LinkedHashMap<>();
        for (Attribute attribute : db.attributes().values()) {
            if (!attribute.shardId().isEmpty()) attributeByShard.putIfAbsent(attribute.shardId(), attribute.id());
        }

        Map<String, Map<String, Integer>> craftingByInternal = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> forgeByInternal    = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> shopByInternal     = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> katByInternal      = new LinkedHashMap<>();
        Map<String, Integer> forgeSecondsByInternal = new LinkedHashMap<>();
        Map<String, Double> buyPriceByInternal = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            switch (recipe.type()) {
                case "crafting" -> collectRecipeIngredients(craftingByInternal, recipe, 0, internalToDisplay, db);
                case "forge" -> {
                    String out = key(recipe.output(), internalToDisplay, db);
                    boolean first = out != null && !forgeByInternal.containsKey(out);
                    collectRecipeIngredients(forgeByInternal, recipe, recipe.coins(), internalToDisplay, db);
                    // Same first-recipe-wins rule as the ingredients, so time and ingredients match.
                    if (first && recipe.timeSeconds() > 0 && forgeByInternal.containsKey(out)) {
                        forgeSecondsByInternal.put(out, recipe.timeSeconds());
                    }
                }
                case "shop" -> {
                    collectRecipeIngredients(shopByInternal, recipe, 0, internalToDisplay, db);
                    collectCoinPrice(buyPriceByInternal, recipe, internalToDisplay, db);
                }
                case "kat" -> collectRecipeIngredients(katByInternal, recipe, recipe.coins(), internalToDisplay, db);
                default -> { }
            }
        }

        // Forge recipes win over crafting recipes for the same item; keeping both would double-count.
        craftingByInternal.keySet().removeAll(forgeByInternal.keySet());
        // Shop purchases only fill gaps (items with no crafting/forge recipe, e.g. the Golden Dragon), and only
        // when they cost items: a coins-only price would turn ordinary materials into "buy it for coins".
        shopByInternal.keySet().removeAll(craftingByInternal.keySet());
        shopByInternal.keySet().removeAll(forgeByInternal.keySet());
        shopByInternal.values().removeIf(cost -> cost.keySet().stream().allMatch(COIN_ID::equals));
        // Kat upgrades (the pet one rarity lower + items + coins) cover most pets above Common. They fill
        // the remaining gaps and travel with the crafting recipes.
        katByInternal.keySet().removeAll(forgeByInternal.keySet());
        katByInternal.keySet().removeAll(shopByInternal.keySet());
        int katCount = 0;
        for (Map.Entry<String, Map<String, Integer>> e : katByInternal.entrySet()) {
            if (craftingByInternal.putIfAbsent(e.getKey(), e.getValue()) == null) katCount++;
        }
        Map<String, String> recipeNameById = new LinkedHashMap<>(internalToDisplay);
        Map<String, Map<String, Integer>> craftingWire = resolveToDisplayNames(craftingByInternal, internalToDisplay, recipeNameById);
        Map<String, Map<String, Integer>> forgeWire    = resolveToDisplayNames(forgeByInternal,    internalToDisplay, recipeNameById);
        Map<String, Map<String, Integer>> shopWire     = resolveToDisplayNames(shopByInternal,     internalToDisplay, recipeNameById);

        // Each file is only rewritten (and the recipes only reloaded) when its content actually changed:
        // the data changes often, the recipes much less.
        boolean changed = false;
        changed |= writeSnapshot(craftingWire, FilePathManager.REMOTE_RECIPES_JSON, meta);
        changed |= writeSnapshot(forgeWire, FilePathManager.REMOTE_FORGE_JSON, meta);
        changed |= writeSnapshot(shopWire, FilePathManager.REMOTE_SHOP_JSON, meta);
        // Base forge time per forge item, under the name its recipe uses (resolved after any renames above).
        Map<String, Integer> forgeSeconds = new LinkedHashMap<>();
        forgeSecondsByInternal.forEach((id, seconds) -> forgeSeconds.put(recipeNameById.getOrDefault(id, id), seconds));
        changed |= writeSnapshot(forgeSeconds, FilePathManager.FORGE_TIMES_JSON, meta);
        Map<String, String> itemTypes = new LinkedHashMap<>();
        typeByInternal.forEach((id, rarityType) -> itemTypes.putIfAbsent(recipeNameById.getOrDefault(id, id), rarityType));
        changed |= writeSnapshot(itemTypes, FilePathManager.ITEM_TYPES_JSON, meta);
        Map<String, String> sacks = itemSacks(meta, recipeNameById);
        if (sacks != null) changed |= writeSnapshot(sacks, FilePathManager.ITEM_SACKS_JSON, meta);
        changed |= writeSnapshot(recipeNameById, FilePathManager.ITEM_NAMES_JSON, meta);
        // What unlocks each recipe the mod uses, e.g. "Coal III" or "Heart of the Mountain 2". Shards are looked up by
        // the attribute they carry.
        Map<String, String> requirements = new LinkedHashMap<>();
        for (String id : craftingOrForge(craftingByInternal, forgeByInternal)) {
            List<String> lines = db.requirements(attributeByShard.getOrDefault(id, id));
            if (!lines.isEmpty()) requirements.putIfAbsent(recipeNameById.getOrDefault(id, id), String.join(" & ", lines));
        }
        changed |= writeSnapshot(requirements, FilePathManager.ITEM_REQUIREMENTS_JSON, meta);
        Map<String, Double> buyPrices = new LinkedHashMap<>();
        buyPriceByInternal.forEach((id, coins) -> buyPrices.put(recipeNameById.getOrDefault(id, id), coins));
        changed |= writeSnapshot(buyPrices, FilePathManager.NPC_BUY_PRICES_JSON, meta);
        boolean pricesChanged = writeSnapshot(npcSellPrices(db, recipeNameById), FilePathManager.NPC_SELL_PRICES_JSON, meta);
        writeMeta(FilePathManager.REMOTE_META_JSON, meta);

        inventoryreader.ir.InventoryReader.debug("SkyblockDB recipes: {} crafting (incl. {} pet upgrades), {} forge, {} shop", craftingWire.size(), katCount, forgeWire.size(), shopWire.size());
        if (changed) {
            inventoryreader.ir.ItemIds.reload();
            inventoryreader.ir.RecipeManager.getInstance().reload();
        } else {
            inventoryreader.ir.InventoryReader.debug("Recipes unchanged");
            if (pricesChanged) inventoryreader.ir.NpcPrices.reload();
        }
    }

    /**
     * Adds an {@code output -> {ingredient: count per output item}} mapping into {@code target}, keyed by internal
     * SkyBlock ID. Only the first recipe per output is kept: items with alternative recipes would otherwise have
     * the ingredients of every alternative summed together. {@code coins} is a coin cost kept outside the inputs
     * (Forge and Kat).
     */
    private static void collectRecipeIngredients(Map<String, Map<String, Integer>> target, Recipe recipe, long coins,
                                                 Map<String, String> names, SkyblockDbApi db) {
        // Outputs go through key() too: a recipe that makes an attribute makes its shard.
        String out = key(recipe.output(), names, db);
        if (out == null || target.containsKey(out)) return;
        double outputCount = Math.max(1, recipe.outputCount());
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> in : recipe.inputs().entrySet()) {
            String id = key(in.getKey(), names, db);
            // Skips free parts.
            if (id == null || in.getValue() <= 0) continue;
            totals.merge(id, (double) in.getValue(), Double::sum);
        }
        if (coins > 0) totals.merge(COIN_ID, (double) coins, Double::sum);
        if (totals.isEmpty()) return;
        Map<String, Integer> ing = new LinkedHashMap<>();
        totals.forEach((id, amount) -> ing.put(id, (int) Math.max(1, Math.ceil(amount / outputCount))));
        target.put(out, ing);
    }

    /**
     * Keeps the cheapest price per item of shop offers that cost only coins (e.g. 8 coins for 2 Coal = 4 each).
     * Offers that also cost items are left out: the NPC price is about coins only.
     */
    private static void collectCoinPrice(Map<String, Double> target, Recipe shop, Map<String, String> names, SkyblockDbApi db) {
        String out = key(shop.output(), names, db);
        if (out == null || shop.inputs().isEmpty()) return;
        double coins = 0;
        for (Map.Entry<String, Integer> in : shop.inputs().entrySet()) {
            if (in.getValue() <= 0) continue;
            if (!COIN_ID.equals(in.getKey())) return;
            coins += in.getValue();
        }
        if (coins <= 0) return;
        target.merge(out, coins / Math.max(1, shop.outputCount()), Math::min);
    }

    /**
     * The key an ingredient is stored under: SkyblockDB's key, except that an attribute stands for its Hunting shard.
     * Kinds that have no item name yet get a readable one in {@code names}: currencies ("SKYBLOCK_COPPER"),
     * enchantments, potions and runes ("ID;level").
     */
    private static String key(String input, Map<String, String> names, SkyblockDbApi db) {
        if (input == null || input.isEmpty()) return null;
        if (names.containsKey(input)) return input;
        Attribute attribute = db.attribute(input).orElse(null);
        if (attribute != null) {
            if (!attribute.shardId().isEmpty() && !attribute.shardName().isEmpty()) {
                names.putIfAbsent(attribute.shardId(), stripMC(attribute.shardName()));
                return attribute.shardId();
            }
            names.putIfAbsent(input, titleCase(input));
            return input;
        }
        if (input.startsWith("SKYBLOCK_")) {
            names.putIfAbsent(input, titleCase(input.substring("SKYBLOCK_".length())));
            return input;
        }
        int semi = input.indexOf(';');
        if (semi > 0) {
            String id = input.substring(0, semi);
            String level = input.substring(semi + 1);
            String name = id.startsWith("POTION_")
                ? titleCase(id.substring("POTION_".length())) + " Potion " + level
                : titleCase(id) + " " + level;
            names.putIfAbsent(input, name);
        }
        return input;
    }

    private static List<String> craftingOrForge(Map<String, ?> crafting, Map<String, ?> forge) {
        List<String> ids = new ArrayList<>(crafting.keySet());
        ids.addAll(forge.keySet());
        return ids;
    }

    /**
     * NPC sell prices by item name, from SkyblockDB (every item in Hypixel's item list). When several items share a
     * name, the one whose ID is that name wins ("Nether Star" -> NETHER_STAR, not CORRUPTED_NETHER_STAR), else the
     * shortest ID, which is the base item rather than a variant ("Melon" -> MELON_BLOCK, not BUILDER_MELON).
     */
    private static Map<String, Double> npcSellPrices(SkyblockDbApi db, Map<String, String> names) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, String> chosen = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : new java.util.TreeMap<>(db.npcSellPrices()).entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String id = e.getKey();
            String name = names.getOrDefault(id, id);
            String current = chosen.get(name);
            if (current == null || better(id, current, name)) {
                out.put(name, e.getValue());
                chosen.put(name, id);
            }
        }
        return out;
    }

    /** Whether {@code id} names the item called {@code name} better than {@code current} does. */
    private static boolean better(String id, String current, String name) {
        String exact = name.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (id.equals(exact) != current.equals(exact)) return id.equals(exact);
        return id.length() < current.length();
    }

    /**
     * Returns a new map with all internal SkyBlock IDs replaced by their display names. Outputs that had
     * to be renamed to stay unique are recorded in {@code recipeNameById}.
     */
    private static Map<String, Map<String, Integer>> resolveToDisplayNames(
            Map<String, Map<String, Integer>> byInternal,
            Map<String, String> internalToDisplay,
            Map<String, String> recipeNameById) {
        Map<String, Map<String, Integer>> wire = new LinkedHashMap<>(byInternal.size());
        // In ID order, so which of two same-named items keeps the plain name never changes between updates; a
        // starred (upgraded) item comes after its base, whose name its recipe uses (VENOMS_TOUCH, then
        // STARRED_VENOMS_TOUCH).
        List<Map.Entry<String, Map<String, Integer>>> entries = new ArrayList<>(byInternal.entrySet());
        entries.sort(java.util.Comparator.<Map.Entry<String, Map<String, Integer>>>comparingInt(
                e -> e.getKey().startsWith("STARRED_") ? 1 : 0)
            .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Map<String, Integer>> e : entries) {
            String outDisplay = internalToDisplay.getOrDefault(e.getKey(), e.getKey());
            // Different items can share a display name (e.g. upgrade stones); keep them apart.
            if (wire.containsKey(outDisplay)) {
                outDisplay = outDisplay + " [" + e.getKey() + "]";
                recipeNameById.put(e.getKey(), outDisplay);
            }
            Map<String, Integer> ingDisplay = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> in : e.getValue().entrySet()) {
                ingDisplay.put(internalToDisplay.getOrDefault(in.getKey(), in.getKey()), in.getValue());
            }
            wire.put(outDisplay, ingDisplay);
        }
        return wire;
    }

    /**
     * Writes {@code data} to {@code dest} unless the file already holds exactly that: the SHA-256 of the JSON is
     * kept in the meta file ("hash::name"). Returns true when the file was (re)written.
     */
    private static boolean writeSnapshot(Map<String, ?> data, File dest, Map<String, String> meta) throws Exception {
        String json = GSON.toJson(data);
        String key = "hash::" + dest.getName();
        String hash = sha256(json);
        if (dest.isFile() && hash.equals(meta.get(key))) return false;
        if (!inventoryreader.ir.JsonFiles.writeText(dest, json)) throw new java.io.IOException("Could not write " + dest.getName());
        meta.put(key, hash);
        return true;
    }

    private static String sha256(String text) throws java.security.NoSuchAlgorithmException {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Map<String, String> readMeta(File f) {
        Map<String, String> m = inventoryreader.ir.JsonFiles.read(f, new TypeToken<Map<String, String>>(){}.getType());
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    private static void writeMeta(File f, Map<String, String> meta) {
        // Temp file and move, so a crash mid-write can't leave a half-written cache state.
        inventoryreader.ir.JsonFiles.write(f, meta);
    }

    /** One entry of NEU's constants/sacks.json: the internal IDs a sack holds. */
    private static class SackDef {
        List<String> contents;
    }

    /**
     * Item name (as the recipes name it) to the sack that holds it ("Enchanted Coal" -> "Enchanted Mining Sack"),
     * from NEU's constants/sacks.json. The file is kept locally and only downloaded again when it changed (ETag).
     * Null when there is no copy at all (keeps the current file).
     */
    private static Map<String, String> itemSacks(Map<String, String> meta, Map<String, String> recipeNameById) {
        File local = FilePathManager.NEU_SACKS_JSON;
        String etagKey = "etag::" + SACKS_URL;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SACKS_URL)).timeout(Duration.ofSeconds(15)).GET();
            String etag = local.isFile() ? meta.getOrDefault(etagKey, "") : "";
            if (!etag.isEmpty()) b.header("If-None-Match", etag);
            HttpResponse<String> resp = inventoryreader.ir.Http.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 == 2 && resp.body() != null && !resp.body().isBlank()) {
                if (inventoryreader.ir.JsonFiles.writeText(local, resp.body())) {
                    resp.headers().firstValue("etag").ifPresent(v -> meta.put(etagKey, v));
                }
            } else if (resp.statusCode() != 304) {
                LOGGER.warn("Sack list fetch HTTP {}", resp.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("Sack list fetch failed: {}", e.toString());
        }
        Map<String, Map<String, SackDef>> root = inventoryreader.ir.JsonFiles.read(local,
            new TypeToken<Map<String, Map<String, SackDef>>>(){}.getType());
        if (root == null || root.get("sacks") == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        root.get("sacks").forEach((sack, def) -> {
            if (def == null || def.contents == null) return;
            for (String id : def.contents) out.putIfAbsent(recipeNameById.getOrDefault(id, id), sack + " Sack");
        });
        return out;
    }

    /** "NORTH_STAR" -> "North Star". */
    private static String titleCase(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String stripMC(String s) {
        return inventoryreader.ir.ItemNames.clean(s);
    }
}
