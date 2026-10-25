package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelSnapTest {
    private static final int[] LINES = {0, 400, 800};

    @Test
    void startSnapsToANearbyLine() {
        assertEquals(new PanelSnap.Result(0, 0), PanelSnap.snap(4, 100, LINES));
    }

    @Test
    void endSnapsToTheScreenEdge() {
        // Right edge at 797 -> 800.
        assertEquals(new PanelSnap.Result(700, 800), PanelSnap.snap(697, 100, LINES));
    }

    @Test
    void middleSnapsToTheCentre() {
        // Middle at 403 -> 400.
        assertEquals(new PanelSnap.Result(350, 400), PanelSnap.snap(353, 100, LINES));
    }

    @Test
    void closestLineWins() {
        // Start (5) is 5 from 0, end (403) is 3 from 400: the end wins.
        assertEquals(new PanelSnap.Result(2, 400), PanelSnap.snap(5, 398, new int[] {0, 400}));
    }

    @Test
    void staysPutWhenNothingIsClose() {
        assertEquals(new PanelSnap.Result(200, -1), PanelSnap.snap(200, 100, LINES));
    }
}
