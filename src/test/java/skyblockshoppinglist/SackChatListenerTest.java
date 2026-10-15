package skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SackChatListenerTest {
    @Test
    void readsSignedLinesAndSectionHeadings() {
        String hover = String.join("\n",
            "Added items:",
            "  +64 Coal (Mining Sack)",
            "  1,234 Enchanted Coal (Mining Sack)",
            "Removed items:",
            "  5 Coal (Mining Sack)",
            "  -2 Titanium (Mining Sack)",
            "This message can be disabled in the settings.");
        Map<String, Integer> deltas = SackChatListener.parseHoverTexts(List.of(hover));
        assertEquals(Map.of("Coal", 59, "Enchanted Coal", 1234, "Titanium", -2), deltas);
    }

    @Test
    void addsUpSeveralHoverLists() {
        Map<String, Integer> deltas = SackChatListener.parseHoverTexts(List.of("+3 Mithril", "+4 Mithril (Mining Sack)"));
        assertEquals(Map.of("Mithril", 7), deltas);
    }

    @Test
    void ignoresZeroAndTextLines() {
        assertEquals(Map.of(), SackChatListener.parseHoverTexts(List.of("Added items:\n+0 Coal\nNothing here")));
    }
}
