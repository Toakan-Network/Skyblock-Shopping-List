package inventoryreader.ir;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the bundled SkyblockAPI library as quiet as this mod: while no other installed mod uses SkyblockAPI, its
 * party-info request to Hypixel and the web requests this mod doesn't need (bazaar and lowest BIN prices, polled
 * every two hours) are blocked by the mixins in {@code mixin/}. When another mod uses SkyblockAPI too, nothing is
 * blocked, so that mod keeps working; the requests are then that mod's.
 */
public final class SkyblockApiGuard {
    private static final String SKYBLOCK_API = "skyblock-api";
    /** The only SkyblockAPI web requests this mod needs: the mayor (Forge times) and Hypixel's item list. */
    private static final List<String> ALLOWED = List.of(
        "https://api.hypixel.net/v2/resources/skyblock/election",
        "https://api.hypixel.net/v2/resources/skyblock/items");
    private static volatile Boolean active;
    /** Addresses already logged as blocked; each is logged once (some repeat every two hours). */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private SkyblockApiGuard() {}

    /** True when this mod is the only one using SkyblockAPI, so its extra traffic is blocked. */
    public static boolean active() {
        Boolean value = active;
        if (value == null) {
            value = !otherModUsesSkyblockApi();
            active = value;
            // Always logged (once): the first request comes at startup, before Debug logging is read.
            if (value) InventoryReader.LOGGER.info("SkyblockAPI: only used by this mod; blocking its unused requests");
            else InventoryReader.LOGGER.info("SkyblockAPI: used by another mod too; not blocking anything");
        }
        return value;
    }

    /** True if a SkyblockAPI web request to {@code url} should not be made. */
    public static boolean blocks(String url) {
        if (!active()) return false;
        for (String allowed : ALLOWED) {
            if (url.startsWith(allowed)) return false;
        }
        int query = url.indexOf('?');
        String address = query >= 0 ? url.substring(0, query) : url;
        if (LOGGED.add(address)) InventoryReader.LOGGER.info("SkyblockAPI: blocked request to {}", address);
        return true;
    }

    private static boolean otherModUsesSkyblockApi() {
        FabricLoader loader = FabricLoader.getInstance();
        // The copy that was loaded may come from another mod's jar (Fabric picks the newest).
        ModContainer api = loader.getModContainer(SKYBLOCK_API).orElse(null);
        if (api != null) {
            String parent = api.getContainingMod().map(m -> m.getMetadata().getId()).orElse(null);
            if (parent != null && !parent.equals(InventoryReader.MOD_ID)) return true;
        }
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals(InventoryReader.MOD_ID) || id.equals(SKYBLOCK_API)) continue;
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                if (dependency.getModId().equals(SKYBLOCK_API)
                    && dependency.getKind() != ModDependency.Kind.BREAKS
                    && dependency.getKind() != ModDependency.Kind.CONFLICTS) {
                    return true;
                }
            }
        }
        return false;
    }
}
