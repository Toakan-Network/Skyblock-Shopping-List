package skyblockshoppinglist;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemIdsTest {
    @Test
    void gemNamesReadsPlainAndMixedSlots() {
        CompoundTag gems = new CompoundTag();
        gems.putString("JADE_0", "FINE");
        gems.putString("UNIVERSAL_0", "PERFECT");
        gems.putString("UNIVERSAL_0_gem", "SAPPHIRE");
        CompoundTag amber = new CompoundTag();
        amber.putString("quality", "FLAWLESS");
        gems.put("AMBER_0", amber);
        // Mixed slot without its gem, unknown quality, and the slot list: all skipped.
        gems.putString("COMBAT_0", "FINE");
        gems.putString("TOPAZ_0", "SHINY");
        gems.putString("unlocked_slots", "JADE_0");
        CompoundTag data = new CompoundTag();
        data.put("gems", gems);

        List<String> names = ItemIds.gemNames(data).stream().sorted().toList();
        assertEquals(List.of("Fine Jade Gemstone", "Flawless Amber Gemstone", "Perfect Sapphire Gemstone"), names);
    }

    @Test
    void noGemsIsEmpty() {
        assertEquals(List.of(), ItemIds.gemNames(new CompoundTag()));
    }
}
