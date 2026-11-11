package dev.notch.cli;

import dev.notch.guard.Decision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An in-memory Notch for tests. */
class FakeBackend implements NotchBackend {

    final List<Decision> decisions = new ArrayList<>(List.of(
            new Decision("d1", "Let's go with Postgres instead of MongoDB because we need joins.", List.of("MongoDB"),
                    Instant.parse("2026-10-05T10:00:00Z"))));
    final List<String> recorded = new ArrayList<>();
    boolean down;

    @Override
    public List<Decision> decisions() throws NotchException {
        if (down) throw new NotchException("Couldn't reach Notch.");
        return decisions;
    }

    @Override
    public String search(String query) {
        return "1. Billing service (2026-10-05, user)\n   Let's go with Postgres instead of MongoDB.";
    }

    @Override
    public String whereWeStopped() {
        return "Last session: Auth refactor (8 Nov)";
    }

    @Override
    public String record(String kind, String title, String rationale, List<String> ruledOut) {
        recorded.add(kind + ": " + title + " | " + rationale + " | " + ruledOut);
        return "m" + recorded.size();
    }

    final List<String> replaced = new ArrayList<>();

    @Override
    public String replace(String decisionId, String title, String rationale, List<String> ruledOut) {
        replaced.add(decisionId + " -> " + title + " | " + ruledOut);
        return "r" + replaced.size();
    }

    @Override
    public String projectName() {
        return "Billing service";
    }
}
