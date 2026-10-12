package skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CountsTest {
    @Test
    void diffReportsGainsLossesAndGoneItems() {
        Map<String, Integer> changes = Counts.diff(Map.of("A", 2, "B", 1, "Same", 4), Map.of("A", 5, "C", 3, "Same", 4));
        assertEquals(Map.of("A", 3, "B", -1, "C", 3), changes);
    }

    @Test
    void diffIgnoresGoneItemsAtZero() {
        assertEquals(Map.of(), Counts.diff(Map.of("A", 0), Map.of()));
    }

    @Test
    void parseReadsCommasAndRejectsText() {
        assertEquals(1234, Counts.parse("1,234"));
        assertEquals(7, Counts.parse(" 7 "));
        assertNull(Counts.parse("abc"));
        assertNull(Counts.parse(""));
    }

    @Test
    void shortNumberRoundsTowardsZero() {
        assertEquals("999", Counts.shortNumber(999));
        assertEquals("1k", Counts.shortNumber(1000));
        assertEquals("5.1k", Counts.shortNumber(5120));
        assertEquals("5.1k", Counts.shortNumber(5199));
        assertEquals("512k", Counts.shortNumber(512_000));
        assertEquals("500m", Counts.shortNumber(500_000_000));
        assertEquals("1.5b", Counts.shortNumber(1_500_000_000));
        assertEquals("-5.1k", Counts.shortNumber(-5120));
    }
}
