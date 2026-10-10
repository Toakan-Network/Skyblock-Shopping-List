package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import skyblockdb.api.SkyblockDbApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs code once SkyblockDB's API is there. Fabric starts mods in no fixed order, so SkyblockDB may not have started
 * when this mod does; then the callbacks wait for the first client tick that finds it.
 */
public final class SkyblockDbHook {
    private static final List<Consumer<SkyblockDbApi>> waiting = new ArrayList<>();
    private static boolean tickRegistered = false;

    private SkyblockDbHook() {}

    /** Calls {@code action} with the API now if SkyblockDB has started, otherwise as soon as it has. Client thread. */
    public static void whenReady(Consumer<SkyblockDbApi> action) {
        SkyblockDbApi api = SkyblockDbApi.get().orElse(null);
        if (api != null) {
            action.accept(api);
            return;
        }
        waiting.add(action);
        if (tickRegistered) return;
        tickRegistered = true;
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (waiting.isEmpty()) return;
            SkyblockDbApi ready = SkyblockDbApi.get().orElse(null);
            if (ready == null) return;
            List<Consumer<SkyblockDbApi>> run = new ArrayList<>(waiting);
            waiting.clear();
            run.forEach(a -> a.accept(ready));
        });
    }
}
