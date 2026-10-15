package skyblockshoppinglist;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcesManagerTest {
    private static final Map<String, Map<String, Integer>> RECIPES = Map.of(
        "Top", Map.of("Mid", 1, "Raw", 2),
        "Mid", Map.of("Low", 1),
        "Low", Map.of("Raw", 1));

    @Test
    void heightCountsCraftingStepsUnderAnItem() {
        Map<String, Integer> memo = new HashMap<>();
        assertEquals(3, ResourcesManager.height("Top", RECIPES, memo, 0));
        assertEquals(2, ResourcesManager.height("Mid", RECIPES, memo, 0));
        assertEquals(0, ResourcesManager.height("Raw", RECIPES, memo, 0));
    }

    @Test
    void heightStopsOnACycle() {
        Map<String, Map<String, Integer>> cyclic = Map.of("A", Map.of("B", 1), "B", Map.of("A", 1));
        assertTrue(ResourcesManager.height("A", cyclic, new HashMap<>(), 0) >= 1);
    }

    @Test
    void stockOrderPutsDeepestFirstAndKeepsTiesInOrder() {
        List<String> names = List.of("Raw", "Low", "Mid", "Other");
        assertEquals(List.of(0, 1, 2, 3), ResourcesManager.stockOrder(names, RECIPES, null));
        assertEquals(List.of(2, 1, 0, 3), ResourcesManager.stockOrder(names, RECIPES, new HashMap<>()));
    }

    @Test
    void levelTotalSumsRowsAcrossTrees() {
        List<RecipeManager.RecipeNode> trees = List.of(tree(), tree());
        RecipeManager.RecipeNode total = ResourcesManager.levelTotalOf(trees, 1);
        assertEquals(4, total.amount);
        assertEquals(16, total.required);
        // Rows still missing come first.
        assertEquals("X", total.ingredients.get(0).name);
        assertEquals(4, total.ingredients.get(0).amount);
        assertEquals(10, total.ingredients.get(0).required);
        assertEquals("Y", total.ingredients.get(1).name);
        assertEquals(6, total.ingredients.get(1).required);
    }

    private static RecipeManager.RecipeNode tree() {
        return new RecipeManager.RecipeNode("R", 1, 1, List.of(
            new RecipeManager.RecipeNode("Y", 0, 3, List.of()),
            new RecipeManager.RecipeNode("X", 2, 5, List.of())));
    }
}
