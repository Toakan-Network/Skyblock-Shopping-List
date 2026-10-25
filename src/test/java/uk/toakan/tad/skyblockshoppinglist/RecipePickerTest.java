package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePickerTest {
    @Test
    void typeGroupsCoverDungeonVariants() {
        assertTrue(RecipePicker.TypeFilter.ARMOR.matches("DUNGEON HELMET"));
        assertTrue(RecipePicker.TypeFilter.TOOL.matches("DRILL"));
        assertFalse(RecipePicker.TypeFilter.TOOL.matches("SWORD"));
    }

    @Test
    void otherIsAnyTypeInNoGroup() {
        assertTrue(RecipePicker.TypeFilter.OTHER.matches("REFORGE STONE"));
        assertFalse(RecipePicker.TypeFilter.OTHER.matches("BOOTS"));
        assertFalse(RecipePicker.TypeFilter.OTHER.matches(""));
        assertTrue(RecipePicker.TypeFilter.ALL.matches(""));
    }

    @Test
    void specialCoversVerySpecial() {
        assertTrue(RecipePicker.RarityFilter.SPECIAL.matches("VERY SPECIAL"));
        assertTrue(RecipePicker.RarityFilter.EPIC.matches("EPIC"));
        assertFalse(RecipePicker.RarityFilter.EPIC.matches("LEGENDARY"));
    }
}
