package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.SkillCatalog;
import org.springframework.stereotype.Service;

/**
 * The skill catalogue, loaded from the database on first use and kept in memory: it changes
 * only through migrations, so it never needs reloading while the server runs.
 */
@Service
public class CatalogService {

    private final PortfolioRepository repo;
    private volatile SkillCatalog catalog;

    public CatalogService(PortfolioRepository repo) {
        this.repo = repo;
    }

    public SkillCatalog catalog() {
        SkillCatalog c = catalog;
        if (c == null) {
            synchronized (this) {
                c = catalog;
                if (c == null) {
                    c = SkillCatalog.of(repo.skills(), repo.aliases());
                    catalog = c;
                }
            }
        }
        return c;
    }
}
