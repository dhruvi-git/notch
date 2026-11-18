package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.DuplicateScreen;
import org.springframework.stereotype.Component;

/**
 * The duplicate screen over every repository fingerprint on Notch, built from the database on
 * first use and kept up to date as repositories are synced. Sized for a million fingerprints at
 * a 1% false-positive target (about 1.2 MB).
 */
@Component
public class FingerprintIndex {

    static final long CAPACITY = 1_000_000;

    private final PortfolioRepository repo;
    private volatile DuplicateScreen screen;

    public FingerprintIndex(PortfolioRepository repo) {
        this.repo = repo;
    }

    public DuplicateScreen screen() {
        DuplicateScreen s = screen;
        if (s == null) {
            synchronized (this) {
                s = screen;
                if (s == null) {
                    s = DuplicateScreen.of(repo.allFingerprints(), CAPACITY);
                    screen = s;
                }
            }
        }
        return s;
    }
}
