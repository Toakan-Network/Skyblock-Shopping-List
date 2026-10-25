package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MayorEventsTest {
    private static List<MobDrops.Source> mobs(String... names) {
        return java.util.Arrays.stream(names).map(n -> new MobDrops.Source(n, 0.1)).toList();
    }

    @Test
    void sharkOnlyDropsNeedAFishingFestival() {
        assertEquals(MayorEvents.Event.FISHING_FESTIVAL, MayorEvents.gatedBy("Shark Fin",
            mobs("Blue Shark", "Great White Shark", "Nurse Shark", "Tiger Shark")));
    }

    @Test
    void dianaMobsNeedTheRitualWhateverTheirTier() {
        assertEquals(MayorEvents.Event.MYTHOLOGICAL_RITUAL, MayorEvents.gatedBy("Daedalus Stick",
            mobs("Exalted Minotaur", "Venerable Minotaur", "Empyrean Minotaur")));
        assertEquals(MayorEvents.Event.MYTHOLOGICAL_RITUAL, MayorEvents.gatedBy("Crown of Greed", mobs("King Minos")));
    }

    @Test
    void anOrdinarySourceMeansNoEvent() {
        assertNull(MayorEvents.gatedBy("Enchanted Gold Ingot", mobs("Minos Inquisitor", "Zombie")));
        assertNull(MayorEvents.gatedBy("Rotten Flesh", mobs("Zombie")));
        assertNull(MayorEvents.gatedBy("Iron Ingot", List.of()));
    }

    @Test
    void fiestaMineralsNeedAMiningFiesta() {
        assertEquals(MayorEvents.Event.MINING_FIESTA, MayorEvents.gatedBy("Refined Mineral", List.of()));
        assertEquals(MayorEvents.Event.MINING_FIESTA, MayorEvents.gatedBy("Glossy Gemstone", List.of()));
    }

    @Test
    void festivalDaysMatchTheFestivalSeenInGame() {
        // latest.log, 2026-08-05 (BST): "The festival is now underway!" at 12:55:04, "has concluded!" at 13:55:01.
        assertTrue(MayorEvents.festivalDay(Instant.parse("2026-08-05T11:55:04Z").toEpochMilli()));
        assertTrue(MayorEvents.festivalDay(Instant.parse("2026-08-05T12:54:00Z").toEpochMilli()));
        assertFalse(MayorEvents.festivalDay(Instant.parse("2026-08-05T12:55:01Z").toEpochMilli()));
        assertFalse(MayorEvents.festivalDay(Instant.parse("2026-08-05T11:54:00Z").toEpochMilli()));
    }

    @Test
    void readsMayorMinisterAndFoxyPerks() {
        String json = """
            {"perks":[{"name":"Fishing XP Buff"},{"name":"Extra Event","description":"Schedules an extra §bMining Fiesta§7 event."}],
             "minister":{"perk":{"name":"Mythological Ritual"}}}""";
        MayorEvents.Perks data = MayorEvents.readPerks(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(List.of("Fishing XP Buff", "Extra Event", "Mythological Ritual"), data.perks());
        assertEquals(List.of("Mining Fiesta"), data.extraEvents());
    }
}
