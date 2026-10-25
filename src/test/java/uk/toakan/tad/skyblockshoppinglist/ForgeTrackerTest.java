package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForgeTrackerTest {
    @BeforeAll
    static void language() {
        ModLanguage.use();
    }

    @Test
    void parseDurationAddsEveryPart() {
        assertEquals((12 * 3600 + 23 * 60 + 50) * 1000L, ForgeTracker.parseDuration("12h 23m 50s"));
        assertEquals(86_400_000L, ForgeTracker.parseDuration("1d"));
        assertNull(ForgeTracker.parseDuration("Ready"));
    }

    @Test
    void formatRemainingPicksTheLargestUnits() {
        assertEquals("Ready", ForgeTracker.formatRemaining(0, 0));
        assertEquals("Ready", ForgeTracker.formatRemaining(0, 5_000));
        assertEquals("<1m", ForgeTracker.formatRemaining(30_000, 0));
        assertEquals("5m", ForgeTracker.formatRemaining(5 * 60_000, 0));
        assertEquals("2h 3m", ForgeTracker.formatRemaining((2 * 60 + 3) * 60_000L, 0));
        assertEquals("1d 4h", ForgeTracker.formatRemaining(28 * 3_600_000L, 0));
    }
}
