package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDropsTest {
    @Test
    void parsePercentReadsNeuChances() {
        assertEquals(0.02, MobDrops.parsePercent("2%"), 1e-12);
        assertEquals(0.4065, MobDrops.parsePercent("40.65%"), 1e-12);
        assertEquals(1e-7, MobDrops.parsePercent("0.00001%"), 1e-15);
        assertNull(MobDrops.parsePercent(""));
        assertNull(MobDrops.parsePercent("2-4"));
        assertNull(MobDrops.parsePercent("150%"));
    }

    @Test
    void magicFindOnlyRaisesDropsBelowFivePercent() {
        assertEquals(0.02 * 3.37, MobDrops.effectiveChance(0.02, 237), 1e-12);
        assertEquals(0.10, MobDrops.effectiveChance(0.10, 237), 1e-12);
        assertEquals(0.02, MobDrops.effectiveChance(0.02, 0), 1e-12);
    }

    @Test
    void killEstimates() {
        assertEquals(100, MobDrops.expectedKills(0.01));
        assertEquals(34, MobDrops.expectedKills(MobDrops.effectiveChance(0.01, 200)));
        // A quarter of players have a 3% drop within 10 kills.
        assertEquals(10, MobDrops.luckyKills(0.03));
        assertEquals(1, MobDrops.luckyKills(1));
    }

    @Test
    void nameplatesGiveTheMobName() {
        assertEquals("Zealot", KillTracker.nameplateName("[Lv55] Zealot 13,000/13,000❤"));
        assertEquals("Enderman", KillTracker.nameplateName("[Lv45] ☠ Enderman 4,500/4,500❤"));
        assertEquals("Voidgloom Seraph", KillTracker.nameplateName("☠ Voidgloom Seraph 50M❤"));
        assertNull(KillTracker.nameplateName("Click to talk"));
    }

    @Test
    void neuVariantsShareTheBaseName() {
        assertEquals("Zealot", MobDrops.baseName("Zealot (Enderchest)"));
        assertEquals("Voidgloom Seraph IV", MobDrops.baseName("Voidgloom Seraph IV"));
    }

    @Test
    void rareDropLinesFromTheLog() {
        Matcher m = KillTracker.RARE_DROP.matcher("RARE DROP! Ender Necklace (+237 ✯ Magic Find)");
        assertTrue(m.matches());
        assertEquals("Ender Necklace", m.group(2));
        assertEquals("237", m.group(3));

        m = KillTracker.RARE_DROP.matcher("RARE DROP! (16x Twilight Arrow Poison) (+130% ✯ Magic Find)");
        assertTrue(m.matches());
        assertEquals("Twilight Arrow Poison", m.group(1));
        assertEquals("130", m.group(3));

        m = KillTracker.RARE_DROP.matcher("RARE DROP! Ender Leggings (+237  Magic Find) (2)");
        assertTrue(m.matches());
        assertEquals("Ender Leggings", m.group(2));

        assertFalse(KillTracker.RARE_DROP.matcher("[MVP+] Someone: RARE DROP! Ender Necklace").matches());
    }
}
