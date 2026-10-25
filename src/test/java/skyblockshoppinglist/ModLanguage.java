package skyblockshoppinglist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Makes the mod's own en_us.json the game language for a test, so code that reads text through {@code I18n} gives
 * the English the player sees. Unit tests have no resource manager to load it the usual way.
 */
final class ModLanguage {
    private static boolean loaded;

    private ModLanguage() {}

    static synchronized void use() {
        if (loaded) return;
        Map<String, String> text = new HashMap<>();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/assets/" + ShoppingList.MOD_ID + "/lang/en_us.json"))).getAsJsonObject();
            json.keySet().forEach(key -> text.put(key, json.get(key).getAsString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Language.inject(new Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                return text.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(String key) {
                return text.containsKey(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return FormattedCharSequence.EMPTY;
            }
        });
        loaded = true;
    }
}
