package inventoryreader.ir.recipes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import inventoryreader.ir.FilePathManager;
import kotlin.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.thatgravyboat.repolib.api.AttributesAPI;
import tech.thatgravyboat.repolib.api.PetsAPI;
import tech.thatgravyboat.repolib.api.RepoAPI;
import tech.thatgravyboat.repolib.api.RepoStatus;
import tech.thatgravyboat.repolib.api.IdOverlaysAPI;
import tech.thatgravyboat.repolib.api.idoverlays.Requirement;
import tech.thatgravyboat.repolib.api.recipes.CraftingRecipe;
import tech.thatgravyboat.repolib.api.recipes.ForgeRecipe;
import tech.thatgravyboat.repolib.api.recipes.KatRecipe;
import tech.thatgravyboat.repolib.api.recipes.Recipe;
import tech.thatgravyboat.repolib.api.recipes.ShopRecipe;
import tech.thatgravyboat.repolib.api.recipes.ingredient.AttributeIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.CraftingIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.CurrencyIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.EnchantmentIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.ItemIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.PetIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.PotionIngredient;
import tech.thatgravyboat.repolib.api.recipes.ingredient.RuneIngredient;
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI;
import tech.thatgravyboat.skyblockapi.api.events.repo.RepoEvent;
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.itemdata.HypixelApiItem;
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.itemdata.ItemData;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the recipe files from the SkyBlock data SkyblockAPI's Repo-Lib downloads and caches (a daily copy of the
 * NotEnoughUpdates repository), plus NPC sell prices from SkyblockAPI's copy of Hypixel's item list. Runs each
 * time Repo-Lib (re)loads. Only NEU's sack list is fetched here, as Repo-Lib doesn't carry it.
 */
public final class RemoteRecipeFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("IR-RemoteRecipeFetcher");
    private static final Gson GSON = new Gson();
    /** Which sack holds which item; the one file Repo-Lib doesn't have. Small, cached with an ETag. */
    private static final String SACKS_URL =
        "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/sacks.json";
    /** Coin costs are keyed like NEU's pseudo item, so names and the Coins resource stay as before. */
    private static final String COIN_ID = "SKYBLOCK_COIN";
    public static final String COINS_NAME = "Coins";
    /** Pet rarities in NEU's order: pets are keyed "BEE;4" (Legendary), as the player's own pets are. */
    private static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    /** Shard ID to its attribute ID, filled while keying ingredients; requirements are looked up by attribute. */
    private static final Map<String, String> ATTRIBUTE_BY_SHARD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean AGAIN = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IR-RecipeFetch");
        t.setDaemon(true);
        return t;
    });

    private RemoteRecipeFetcher() {}

    /** Builds the recipes whenever Repo-Lib (re)loads, and now if it already has. */
    public static void register() {
        SkyBlockAPI.getEventBus().register(RepoEvent.Reload.class, 0, false, event -> {
            if (event.getStatus() == RepoStatus.SUCCESS) fetchAsync();
            return Unit.INSTANCE;
        });
        if (RepoAPI.isInitialized()) fetchAsync();
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
        List<CraftingRecipe> crafting = RepoAPI.recipes().getRecipes(Recipe.Type.CRAFTING);
        if (crafting.isEmpty()) {
            LOGGER.warn("Repo-Lib has no recipes yet; keeping the current files");
            return;
        }
        Map<String, String> meta = readMeta(FilePathManager.REMOTE_META_JSON);

        Map<String, String> internalToDisplay = new LinkedHashMap<>();
        Map<String, String> typeByInternal = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : RepoAPI.items().items().entrySet()) {
            JsonObject components = object(e.getValue(), "components");
            String display = stripMC(text(object(components, "minecraft:custom_name")));
            if (display != null && !display.isBlank()) internalToDisplay.putIfAbsent(e.getKey(), display);
            // Rarity and type from the last lore line ("LEGENDARY ACCESSORY"), for the List tab filters.
            String rarityType = rarityType(loreLines(components));
            if (rarityType != null) typeByInternal.putIfAbsent(e.getKey(), rarityType);
        }
        for (Map.Entry<String, PetsAPI.Data> e : RepoAPI.pets().pets().entrySet()) {
            for (String tier : e.getValue().tiers().keySet()) {
                String id = petKey(e.getKey(), tier);
                if (id != null) internalToDisplay.putIfAbsent(id, e.getValue().name() + " (" + titleCase(tier) + ")");
            }
        }
        internalToDisplay.put(COIN_ID, COINS_NAME);

        Map<String, Map<String, Integer>> craftingByInternal = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> forgeByInternal    = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> shopByInternal     = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> katByInternal      = new LinkedHashMap<>();
        Map<String, Integer> forgeSecondsByInternal = new LinkedHashMap<>();
        Map<String, Double> buyPriceByInternal = new LinkedHashMap<>();
        for (CraftingRecipe recipe : crafting) {
            collectRecipeIngredients(craftingByInternal, recipe.result(), recipe.inputs(), 0, internalToDisplay);
        }
        for (ForgeRecipe recipe : RepoAPI.recipes().getRecipes(Recipe.Type.FORGE)) {
            String out = key(recipe.result(), internalToDisplay);
            boolean first = out != null && !forgeByInternal.containsKey(out);
            collectRecipeIngredients(forgeByInternal, recipe.result(), recipe.inputs(), recipe.coins(), internalToDisplay);
            // Same first-recipe-wins rule as the ingredients, so time and ingredients match.
            if (first && recipe.time() > 0 && forgeByInternal.containsKey(out)) forgeSecondsByInternal.put(out, recipe.time());
        }
        for (ShopRecipe recipe : RepoAPI.recipes().getRecipes(Recipe.Type.SHOP)) {
            collectRecipeIngredients(shopByInternal, recipe.result(), recipe.inputs(), 0, internalToDisplay);
            collectCoinPrice(buyPriceByInternal, recipe, internalToDisplay);
        }
        for (KatRecipe recipe : RepoAPI.recipes().getRecipes(Recipe.Type.KAT)) {
            List<CraftingIngredient> inputs = new ArrayList<>(recipe.items());
            inputs.add(recipe.input());
            collectRecipeIngredients(katByInternal, recipe.output(), inputs, recipe.coins(), internalToDisplay);
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
        // What unlocks each recipe the mod uses, e.g. "Coal III" or "Heart of the Mountain 2".
        Map<String, String> requirements = new LinkedHashMap<>();
        for (String id : craftingOrForge(craftingByInternal, forgeByInternal)) {
            String text = requirementText(overlay(id));
            if (text != null) requirements.putIfAbsent(recipeNameById.getOrDefault(id, id), text);
        }
        changed |= writeSnapshot(requirements, FilePathManager.ITEM_REQUIREMENTS_JSON, meta);
        Map<String, Double> buyPrices = new LinkedHashMap<>();
        buyPriceByInternal.forEach((id, coins) -> buyPrices.put(recipeNameById.getOrDefault(id, id), coins));
        changed |= writeSnapshot(buyPrices, FilePathManager.NPC_BUY_PRICES_JSON, meta);
        boolean pricesChanged = writeSnapshot(npcSellPrices(recipeNameById), FilePathManager.NPC_SELL_PRICES_JSON, meta);
        writeMeta(FilePathManager.REMOTE_META_JSON, meta);

        inventoryreader.ir.InventoryReader.debug("Repo-Lib recipes: {} crafting (incl. {} pet upgrades), {} forge, {} shop", craftingWire.size(), katCount, forgeWire.size(), shopWire.size());
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
    private static void collectRecipeIngredients(Map<String, Map<String, Integer>> target, CraftingIngredient output,
                                                 List<CraftingIngredient> inputs, int coins,
                                                 Map<String, String> names) {
        String out = key(output, names);
        if (out == null || inputs == null || target.containsKey(out)) return;
        double outputCount = Math.max(1, output.count());
        Map<String, Double> totals = new LinkedHashMap<>();
        for (CraftingIngredient in : inputs) {
            String id = key(in, names);
            // Skips empty slots and free parts.
            if (id == null || in.count() <= 0) continue;
            totals.merge(id, (double) in.count(), Double::sum);
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
    private static void collectCoinPrice(Map<String, Double> target, ShopRecipe shop, Map<String, String> names) {
        String out = key(shop.result(), names);
        if (out == null || shop.inputs() == null) return;
        double coins = 0;
        for (CraftingIngredient in : shop.inputs()) {
            String id = key(in, names);
            if (id == null || in.count() <= 0) continue;
            if (!COIN_ID.equals(id)) return;
            coins += in.count();
        }
        if (coins <= 0) return;
        target.merge(out, coins / Math.max(1, shop.result().count()), Math::min);
    }

    /**
     * The key an ingredient is stored under: its SkyBlock ID, "BEE;4" for pets, "SKYBLOCK_COIN" style for
     * currencies, "ID;level" for enchantments, potions and runes. Kinds that have no item name yet get a readable
     * one in {@code names}. Null for empty slots and unknown kinds.
     */
    private static String key(CraftingIngredient in, Map<String, String> names) {
        if (in == null) return null;
        String key;
        String name;
        switch (in) {
            case ItemIngredient item -> {
                return item.id();
            }
            case PetIngredient pet -> {
                return petKey(pet.id(), pet.tier());
            }
            case CurrencyIngredient currency -> {
                if ("COIN".equals(currency.currency())) return COIN_ID;
                key = "SKYBLOCK_" + currency.currency();
                name = titleCase(currency.currency());
            }
            case EnchantmentIngredient enchantment -> {
                key = enchantment.id() + ";" + enchantment.level();
                name = titleCase(enchantment.id()) + " " + enchantment.level();
            }
            case PotionIngredient potion -> {
                key = potion.id() + ";" + potion.level();
                name = titleCase(potion.id().replaceFirst("^POTION_", "")) + " Potion " + potion.level();
            }
            case RuneIngredient rune -> {
                key = rune.id() + ";" + rune.tier();
                name = titleCase(rune.id()) + " Rune " + rune.tier();
            }
            case AttributeIngredient attribute -> {
                // An attribute stands for its Hunting shard ("HONEY_REFILL" -> Queen Bee Shard, SHARD_QUEEN_BEE).
                AttributesAPI.Attribute data = RepoAPI.attributes().getAttribute(attribute.id());
                if (data != null && data.shardId() != null && data.shardName() != null) {
                    key = data.shardId();
                    name = stripMC(data.shardName());
                    ATTRIBUTE_BY_SHARD.put(key, attribute.id());
                } else {
                    key = attribute.id();
                    name = titleCase(attribute.id());
                }
            }
            default -> {
                return null;
            }
        }
        names.putIfAbsent(key, name);
        return key;
    }

    /** "BEE" + "LEGENDARY" -> "BEE;4", NEU's pet key; null for a tier outside Common..Mythic. */
    private static String petKey(String id, String tier) {
        if (id == null || tier == null) return null;
        int index = PET_TIERS.indexOf(tier.toUpperCase(Locale.ROOT));
        return index < 0 ? null : id + ";" + index;
    }

    private static List<String> craftingOrForge(Map<String, ?> crafting, Map<String, ?> forge) {
        List<String> ids = new ArrayList<>(crafting.keySet());
        ids.addAll(forge.keySet());
        return ids;
    }

    /** The overlay holding an item's requirements: pets and shards are looked up by pet tier and attribute. */
    private static IdOverlaysAPI.OverlayData overlay(String id) {
        int semi = id.indexOf(';');
        if (semi > 0) {
            int tier = parseInt(id.substring(semi + 1));
            if (tier >= 0 && tier < PET_TIERS.size()) return RepoAPI.overlays().getPet(id.substring(0, semi), PET_TIERS.get(tier));
        }
        String attribute = ATTRIBUTE_BY_SHARD.get(id);
        if (attribute != null) return RepoAPI.overlays().getAttribute(attribute);
        return RepoAPI.overlays().getItem(id);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The recipe unlock requirements as one line ("Coal VII", "Gemstone X & HotM 5"), or null when there are none.
     * Only entries with NEU's own text: those come from its "Requires:" crafting line. The others are what it
     * takes to use the item (e.g. Spider Slayer 5 to wear Tarantula armour), not to unlock its recipe.
     */
    private static String requirementText(IdOverlaysAPI.OverlayData overlay) {
        if (overlay == null || overlay.requirements() == null) return null;
        List<String> parts = new ArrayList<>();
        for (Requirement requirement : overlay.requirements()) {
            String text = stripMC(requirement.rawString());
            if (text != null && !text.isBlank() && !parts.contains(text.trim())) parts.add(text.trim());
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    /** NPC sell prices by item name, from SkyblockAPI's copy of Hypixel's item list (it downloads it anyway). */
    private static Map<String, Double> npcSellPrices(Map<String, String> names) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, HypixelApiItem> e : ItemData.INSTANCE.getData().entrySet()) {
            Float price = e.getValue().getNpcSellPriceFloat();
            if (price == null || price <= 0) continue;
            // Through the float's own text, so 0.3 stays 0.3 instead of 0.30000001192092896.
            out.put(names.getOrDefault(e.getKey(), e.getKey()), Double.parseDouble(price.toString()));
        }
        return out;
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

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement e = parent.get(name);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String text(JsonObject component) {
        if (component == null) return null;
        JsonElement e = component.get("text");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static List<String> loreLines(JsonObject components) {
        List<String> out = new ArrayList<>();
        if (components == null) return out;
        JsonElement lore = components.get("minecraft:lore");
        if (lore == null || !lore.isJsonArray()) return out;
        for (JsonElement line : (JsonArray) lore) {
            if (line.isJsonObject()) out.add(text(line.getAsJsonObject()));
            else if (line.isJsonPrimitive()) out.add(line.getAsString());
        }
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

    private static final List<String> RARITIES = List.of("VERY SPECIAL", "UNCOMMON", "COMMON", "RARE", "EPIC",
        "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "ULTIMATE", "SUPREME", "ADMIN");

    /**
     * "RARITY|TYPE" from an item's last lore line ("EPIC DUNGEON HELMET" -> "EPIC|DUNGEON HELMET", "COMMON" ->
     * "COMMON|"), or null when that line doesn't start with a rarity. Recombobulated items wrap the line in an
     * obfuscated "a", which is dropped.
     */
    private static String rarityType(List<String> lore) {
        if (lore == null) return null;
        String last = null;
        for (String line : lore) {
            String clean = stripMC(line);
            if (clean != null && !clean.isBlank()) last = clean.trim();
        }
        if (last == null) return null;
        if (last.startsWith("a ")) last = last.substring(2);
        if (last.endsWith(" a")) last = last.substring(0, last.length() - 2);
        for (String rarity : RARITIES) {
            if (last.equals(rarity) || last.startsWith(rarity + " ")) {
                return rarity + "|" + last.substring(rarity.length()).trim();
            }
        }
        return null;
    }

    private static String stripMC(String s) {
        return inventoryreader.ir.ItemNames.clean(s);
    }
}
