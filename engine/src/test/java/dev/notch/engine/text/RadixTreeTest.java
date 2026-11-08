package dev.notch.engine.text;

import dev.notch.engine.baseline.Baselines;
import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RadixTreeTest {

    private static List<String> keys(List<RadixTree.Entry> entries) {
        return entries.stream().map(RadixTree.Entry::key).toList();
    }

    @Test
    void insertAndContains() {
        RadixTree t = new RadixTree(new RandomizedSelect(1));
        assertTrue(t.insert("useEffect", 3));
        assertTrue(t.insert("useState", 5));
        assertTrue(t.contains("useEffect"));
        assertFalse(t.contains("use"));
        assertEquals(2, t.size());
    }

    @Test
    void splitsEdgesAndKeepsBothKeys() {
        RadixTree t = new RadixTree();
        t.insert("test", 1);
        t.insert("team", 1);
        t.insert("te", 1);
        assertTrue(t.contains("test") && t.contains("team") && t.contains("te"));
        assertFalse(t.contains("tea"));
        assertEquals(3, t.nodeCount());  // "te" -> {"st", "am"}
    }

    @Test
    void completeReturnsHeaviestFirst() {
        RadixTree t = new RadixTree(new RandomizedSelect(2));
        t.insert("src/api/AuthController.java", 2);
        t.insert("src/api/AuthService.java", 9);
        t.insert("src/web/App.tsx", 7);
        t.insert("src/api/User.java", 5);
        assertEquals(List.of("src/api/AuthService.java", "src/api/User.java"), keys(t.complete("src/api", 2)));
        assertEquals(List.of("src/api/AuthService.java", "src/api/AuthController.java"), keys(t.complete("src/api/Au", 5)));
    }

    @Test
    void prefixEndingMidEdgeAndMissingPrefix() {  // boundary
        RadixTree t = new RadixTree();
        t.insert("kubectl", 1);
        assertEquals(List.of("kubectl"), keys(t.complete("kub", 3)));
        assertEquals(List.of("kubectl"), keys(t.complete("kubectl", 3)));
        assertTrue(t.complete("kubectx", 3).isEmpty());
        assertTrue(t.complete("docker", 3).isEmpty());
    }

    @Test
    void emptyPrefixReturnsAllKeysUpToK() {  // boundary
        RadixTree t = new RadixTree();
        t.insert("a", 1);
        t.insert("b", 2);
        t.insert("c", 3);
        assertEquals(List.of("c", "b"), keys(t.complete("", 2)));
        assertTrue(new RadixTree().complete("", 5).isEmpty());
    }

    @Test
    void reinsertUpdatesWeightWithoutGrowing() {
        RadixTree t = new RadixTree();
        assertTrue(t.insert("redis", 1));
        assertFalse(t.insert("redis", 8));
        assertEquals(1, t.size());
        assertEquals(8, t.weight("redis"));
        assertTrue(Double.isNaN(t.weight("missing")));
    }

    @Test
    void removeMergesNodesAndKeepsOthers() {
        RadixTree t = new RadixTree();
        t.insert("test", 1);
        t.insert("team", 1);
        assertTrue(t.remove("team"));
        assertFalse(t.contains("team"));
        assertTrue(t.contains("test"));
        assertEquals(1, t.nodeCount());  // "te" + "st" merged back into "test"
        assertFalse(t.remove("team"));
        assertFalse(t.remove("tes"));
    }

    @Test
    void rejectsInvalidInput() {
        RadixTree t = new RadixTree();
        assertThrows(IllegalArgumentException.class, () -> t.insert("", 1));
        assertThrows(IllegalArgumentException.class, () -> t.insert("x", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.complete("x", 0));
        assertThrows(NullPointerException.class, () -> t.complete(null, 3));
    }

    @Test
    void matchesLinearScanOnRandomKeys() {  // stress against the baseline
        Random rnd = new Random(11);
        RadixTree t = new RadixTree(new RandomizedSelect(3));
        Map<String, Double> ref = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            String key = SuffixArrayTest.randomString(rnd, 1 + rnd.nextInt(8), 3);
            double w = rnd.nextInt(1000);
            t.insert(key, w);
            ref.put(key, w);
            if (rnd.nextInt(10) == 0) {
                String gone = SuffixArrayTest.randomString(rnd, 1 + rnd.nextInt(8), 3);
                assertEquals(ref.remove(gone) != null, t.remove(gone));
            }
        }
        assertEquals(ref.size(), t.size());
        for (int q = 0; q < 200; q++) {
            String prefix = SuffixArrayTest.randomString(rnd, rnd.nextInt(4), 3);
            assertEquals(Baselines.linearPrefixScan(ref, prefix, 10), keys(t.complete(prefix, 10)), prefix);
        }
    }
}
