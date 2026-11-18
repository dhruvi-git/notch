package dev.notch.api.portfolio.core;

import dev.notch.api.portfolio.core.DuplicateScreen.Owner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateScreenTest {

    private static final UUID ASHA = UUID.randomUUID(), BEN = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void flagsAnotherUsersEarlierClaimAndNotOwnResync() {
        Owner ashaRepo = new Owner(UUID.randomUUID(), ASHA, T0);
        Map<String, List<Owner>> db = Map.of("root-a", List.of(ashaRepo), "tree-a", List.of(ashaRepo));
        DuplicateScreen s = DuplicateScreen.of(db.keySet(), 1000);

        assertEquals(ashaRepo, s.check(BEN, "root-a", "other-tree", sha -> db.getOrDefault(sha, List.of())).orElseThrow());
        assertEquals(ashaRepo, s.check(BEN, "fresh-root", "tree-a", sha -> db.getOrDefault(sha, List.of())).orElseThrow());
        assertTrue(s.check(ASHA, "root-a", "tree-a", sha -> db.getOrDefault(sha, List.of())).isEmpty());
    }

    @Test
    void definitelyNewSkipsTheDatabase() {
        DuplicateScreen s = DuplicateScreen.of(List.of("known"), 1000);
        int[] lookups = {0};
        assertTrue(s.check(BEN, "brand-new-root", null, sha -> {
            lookups[0]++;
            return List.of();
        }).isEmpty());
        assertTrue(lookups[0] <= 1); // at most a rare false positive
    }

    @Test
    void falsePositiveRateStaysNearTarget() {
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) known.add("known-" + i);
        DuplicateScreen s = DuplicateScreen.of(known, 10_000);
        for (int i = 0; i < 20_000; i++) s.check(BEN, "unseen-" + i, null, sha -> List.of());
        double rate = (double) s.falsePositives() / s.checks();
        assertTrue(rate < 0.02, "false-positive rate " + rate);
        assertEquals(s.hints(), s.falsePositives()); // every hint was unconfirmed
    }

    @Test
    void picksTheEarliestClaimWhenSeveralMatch() {
        Owner early = new Owner(UUID.randomUUID(), ASHA, T0), late = new Owner(UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(60));
        Map<String, List<Owner>> db = new HashMap<>();
        db.put("r", List.of(late, early));
        DuplicateScreen s = DuplicateScreen.of(db.keySet(), 100);
        assertEquals(early, s.check(BEN, "r", null, sha -> db.getOrDefault(sha, List.of())).orElseThrow());
    }

    @Test
    void edgeCaseMissingFingerprintsAndLaterAdds() {
        DuplicateScreen s = DuplicateScreen.of(List.of(), 100);
        assertTrue(s.check(BEN, null, " ", sha -> fail("no lookup expected")).isEmpty());
        assertEquals(0, s.checks());
        s.add("root-new", null);
        Owner o = new Owner(UUID.randomUUID(), ASHA, T0);
        assertEquals(o, s.check(BEN, "root-new", null, sha -> List.of(o)).orElseThrow());
        assertThrows(NullPointerException.class, () -> s.check(null, "x", null, sha -> List.of()));
    }
}
