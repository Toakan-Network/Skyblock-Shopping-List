package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNamesTest {
    @Test
    void cleanDropsFormattingGlyphsAndExtraSpaces() {
        assertEquals("☘ Fine Jade Gemstone", ItemNames.clean("§a☘ Fine  Jade Gemstone✪✪"));
        assertEquals("Perfect Sapphire Gemstone", ItemNames.clean(" Perfect Sapphire Gemstone"));
        assertEquals("", ItemNames.clean(null));
    }

    @Test
    void normalizeMatchesEverySpelling() {
        assertEquals("fine jade gemstone", ItemNames.normalize("☘ Fine Jade Gemstone"));
        assertEquals("fine jade gemstone", ItemNames.normalize("Fine Jade Gemstone"));
        assertEquals("cobblestone", ItemNames.normalize("§fCobblestone"));
    }

    @Test
    void junkIsDigitsOrBlank() {
        assertTrue(ItemNames.isJunk(null));
        assertTrue(ItemNames.isJunk("  "));
        assertTrue(ItemNames.isJunk("123"));
        assertFalse(ItemNames.isJunk("FTX 3070"));
    }
}
