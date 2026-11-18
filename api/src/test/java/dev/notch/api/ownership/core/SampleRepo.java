package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.RegionExtractor.SourceFile;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.portfolio.core.SkillCatalog.Skill;

import java.util.List;
import java.util.Map;

/** A small multi-language repository, like a fresher's project, for the Ownership Check tests. */
final class SampleRepo {

    static final SkillCatalog CATALOG = SkillCatalog.of(List.of(
            new Skill(1, "Python", "Languages"), new Skill(2, "Java", "Languages"), new Skill(4, "TypeScript", "Languages"),
            new Skill(17, "SQL", "Data"), new Skill(20, "pandas", "Data"), new Skill(42, "scikit-learn", "AI & ML"),
            new Skill(60, "React", "Web"), new Skill(72, "Spring Boot", "Web")),
            Map.of("sklearn", 42));

    static final String CHURN_PY = """
            import pandas as pd
            from sklearn.model_selection import train_test_split
            from sklearn.linear_model import LogisticRegression


            def load_customers(path):
                frame = pd.read_csv(path)
                frame = frame.dropna(subset=["tenure"])
                frame["monthly_spend"] = frame["monthly_spend"].fillna(0)
                return frame


            def train_churn_model(frame):
                features = frame[["tenure", "monthly_spend"]]
                labels = frame["churned"]
                train_x, test_x, train_y, test_y = train_test_split(features, labels, test_size=0.2)
                model = LogisticRegression()
                model.fit(train_x, train_y)
                accuracy = model.score(test_x, test_y)
                return model, accuracy


            def x(a):
                return a
            """;

    static final String DASHBOARD_TSX = """
            import { useEffect, useState } from "react";

            export const Dashboard = ({ storeId }: { storeId: string }) => {
              const [sales, setSales] = useState<number[]>([]);
              useEffect(() => {
                loadSales(storeId).then(setSales);
              }, [storeId]);
              return <Chart points={sales} />;
            };

            async function loadSales(storeId: string): Promise<number[]> {
              const res = await fetch(`/api/sales/${storeId}`);
              if (!res.ok) throw new Error("sales failed");
              return res.json();
            }
            """;

    static final String TREE_JAVA = """
            package shop;

            public class CategoryTree {
                private final java.util.Map<String, CategoryTree> children = new java.util.HashMap<>();
                private int products;

                public int totalProducts() {
                    int total = products;
                    for (CategoryTree child : children.values()) {
                        total += child.totalProducts();
                    }
                    return total;
                }

                public void add(String path, int count) {
                    String[] parts = path.split("/");
                    CategoryTree node = this;
                    for (String part : parts) {
                        node = node.children.computeIfAbsent(part, k -> new CategoryTree());
                    }
                    node.products += count;
                }

                public String describe() {
                    return "tree with " + children.size() + " children and " + totalProducts() + " products";
                }
            }
            """;

    static final String REPORT_SQL = """
            -- Monthly revenue per store
            SELECT s.name, date_trunc('month', o.created_at) AS month, SUM(o.amount) AS revenue
            FROM orders o
            LEFT JOIN stores s ON s.id = o.store_id
            GROUP BY s.name, month
            ORDER BY month;

            SELECT 1;
            """;

    static final List<SourceFile> FILES = List.of(
            new SourceFile("model/churn.py", CHURN_PY),
            new SourceFile("web/Dashboard.tsx", DASHBOARD_TSX),
            new SourceFile("src/CategoryTree.java", TREE_JAVA),
            new SourceFile("sql/report.sql", REPORT_SQL),
            new SourceFile("README.md", "# Sales project\n\nNot code."));

    private SampleRepo() {
    }
}
