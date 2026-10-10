package inventoryreader.ir;

import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI;
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription;
import tech.thatgravyboat.skyblockapi.api.events.hypixel.ChangedSackItem;
import tech.thatgravyboat.skyblockapi.api.events.hypixel.SacksChangeEvent;
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent;

/**
 * Logs SkyblockAPI events (with Debug logging on) to check the library reaches this mod. Nothing here changes
 * counts yet; the readers move over to SkyblockAPI step by step.
 */
public final class SkyblockApiProbe {
    private SkyblockApiProbe() {}

    public static void register() {
        SkyBlockAPI.getEventBus().register(new SkyblockApiProbe());
    }

    @Subscription
    private void onProfile(ProfileChangeEvent event) {
        InventoryReader.debug("SkyblockAPI: profile {}", event.getName());
    }

    @Subscription
    private void onSacks(SacksChangeEvent event) {
        for (ChangedSackItem item : event.getChangedItems()) {
            InventoryReader.debug("SkyblockAPI: sack {} {}", item.getItem(), item.getDiff());
        }
    }
}
