package uk.toakan.tad.skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecipeManagerTest {
    @Test
    void sanitizeDropsBadRecipesAndBreaksCycles() {
        Map<String, Map<String, Integer>> input = new LinkedHashMap<>();
        input.put("Empty", Map.of());
        input.put("Coal", Map.of("Block of Coal", 1));
        input.put("White Wool", new LinkedHashMap<>(Map.of("White Wool", 1, "String", 4)));
        input.put("Junk", Map.of("123", 1));
        input.put("A", Map.of("B", 1));
        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("A", 2);
        b.put("X", 1);
        input.put("B", b);

        Map<String, Map<String, Integer>> out = RecipeManager.sanitizeRecipes(input);

        assertFalse(out.containsKey("Empty"));
        assertFalse(out.containsKey("Coal"));
        assertFalse(out.containsKey("Junk"));
        assertEquals(Map.of("String", 4), out.get("White Wool"));
        assertEquals(Map.of("B", 1), out.get("A"));
        // B -> A closed the loop A -> B -> A; only that edge goes.
        assertEquals(Map.of("X", 1), out.get("B"));
    }

    @Test
    void craftsRoundUpWhenACraftMakesSeveral() {
        Map<String, Integer> yields = Map.of("Blaze Powder", 2);
        assertEquals(211, RecipeManager.crafts(yields, "Blaze Powder", 422));
        assertEquals(212, RecipeManager.crafts(yields, "Blaze Powder", 423));
        assertEquals(0, RecipeManager.crafts(yields, "Blaze Powder", 0));
        assertEquals(7, RecipeManager.crafts(yields, "Enchanted Eye of Ender", 7));
    }
}
