package uk.toakan.tad.skyblockshoppinglist.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import uk.toakan.tad.skyblockshoppinglist.ConfigScreen;

/**
 * Mod Menu's "Configure" button for this mod, opening the Settings screen. Loaded only by Mod Menu through the
 * "modmenu" entrypoint, so this is the only class that may touch its API.
 */
public final class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::create;
    }
}
