package dev.notch.engine.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinCostFlowTest {

    @Test
    void sendsTheMaximumFlowAlongTheCheapestRoutes() {
        // s=0, t=3. Two units can reach t: one via 1 (cost 1+1), one via 2 (cost 2+1); the 1->2 shortcut
        // only matters when it is cheaper.
        MinCostFlow net = new MinCostFlow(4);
        int s1 = net.addEdge(0, 1, 1, 1);
        int s2 = net.addEdge(0, 2, 1, 2);
        int a = net.addEdge(1, 3, 1, 1);
        int b = net.addEdge(2, 3, 1, 1);
        net.addEdge(1, 2, 1, 5);
        MinCostFlow.Result r = net.solve(0, 3);
        assertEquals(2, r.flow());
        assertEquals(5, r.cost());
        assertEquals(1, net.flowOn(s1));
        assertEquals(1, net.flowOn(s2));
        assertEquals(1, net.flowOn(a));
        assertEquals(1, net.flowOn(b));
    }

    @Test
    void reroutesEarlierFlowWhenThatIsCheaperOverall() {
        // The first cheapest path takes 0-1-2-3 (cost -10). The second unit can only arrive by undoing
        // the 1->2 step, which the reverse edge allows: final flows 0-1-3 and 0-2-3.
        MinCostFlow net = new MinCostFlow(4);
        net.addEdge(0, 1, 1, 0);
        net.addEdge(0, 2, 1, 0);
        int mid = net.addEdge(1, 2, 1, -10);
        net.addEdge(1, 3, 1, 0);
        net.addEdge(2, 3, 1, 0);
        MinCostFlow.Result r = net.solve(0, 3);
        assertEquals(2, r.flow());
        assertEquals(0, r.cost());
        assertEquals(0, net.flowOn(mid));
        assertEquals(2, net.augmentations());
    }

    @Test
    void handlesNoPathAndRejectsNegativeCycles() {
        MinCostFlow empty = new MinCostFlow(3);
        empty.addEdge(0, 1, 5, 1);
        assertEquals(new MinCostFlow.Result(0, 0), empty.solve(0, 2));

        MinCostFlow cycle = new MinCostFlow(3);
        cycle.addEdge(0, 1, 1, 0);
        cycle.addEdge(1, 2, 1, -1);
        cycle.addEdge(2, 1, 1, -1);
        assertThrows(IllegalArgumentException.class, () -> cycle.solve(0, 2));
    }

    @Test
    void rejectsMisuse() {
        assertThrows(IllegalArgumentException.class, () -> new MinCostFlow(1));
        MinCostFlow net = new MinCostFlow(2);
        assertThrows(IllegalArgumentException.class, () -> net.addEdge(0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> net.addEdge(0, 1, -1, 0));
        int e = net.addEdge(0, 1, 1, 3);
        assertThrows(IllegalStateException.class, () -> net.flowOn(e));
        net.solve(0, 1);
        assertThrows(IllegalStateException.class, () -> net.solve(0, 1));
        assertThrows(IllegalStateException.class, () -> net.addEdge(0, 1, 1, 0));
    }
}
