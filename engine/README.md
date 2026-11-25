# Notch algorithm engine

Every assessed algorithm in Notch lives here, written from scratch in Java 21. The module has
no runtime dependencies; JUnit 5 is used for tests only. Spring Boot, Postgres and pgvector
in the rest of the project provide infrastructure and never replace these classes.

## Algorithms

| Class | Technique | Unit | Used for | Time | Space |
|---|---|---|---|---|---|
| `text.SuffixArray` | Suffix array, prefix doubling with counting sort | 4 | Exact search over all conversations | Build O(n log n), query O(m log n) | O(n) |
| `text.CorpusIndex` | Suffix array over joined documents | 4 | Error codes, identifiers, paths; per-document offsets; a review issue's "seen before" history | As above, plus O(log d) per hit to find its document | O(n) |
| `text.Kmp` | Knuth–Morris–Pratt | 4 | Highlighting cited spans; secret detection | O(n + m) | O(m) |
| `text.SecretScanner` | KMP on key prefixes + Shannon entropy | 4 | Redacting credentials before storage | O(n · r) for r rules | O(n) |
| `text.RadixTree` | Compressed trie with weighted keys | 4 | `@` autocomplete for files (from the code map), symbols and chats | Insert/lookup O(L); complete O(p + s) expected for s matches | O(total key length) |
| `probabilistic.BloomFilter` | Bloom filter, double hashing | 6 (CO5) | Skipping duplicate chunks before embedding | O(k) per add/check | m bits |
| `select.RandomizedSelect` | Randomized Quickselect, 3-way partition | 6 (CO5) | Top-k results; the files a code review reads; start-here files; percentiles | Expected O(n + k log k) | O(n) copy |
| `graph.DisjointSetUnion` | Union by rank + path halving | 2 | Thread stitching | O(α(n)) amortized | O(n) |
| `graph.ThreadStitcher` | Union-find over shared link keys | 2 | Grouping sessions about the same work ("related sessions" on review issues) | O(keys · α(n)) per conversation | O(n + keys) |
| `rank.HybridRanker` | Weighted signal fusion + Quickselect | 6 | Final ranking with per-signal explanations | Expected O(n + k log k) | O(n) |
| `flow.MaxFlow` | Edmonds–Karp max-flow (BFS augmenting paths, paired reverse edges) | 5 (CO4) | Seats filled in a hiring drive; the comparison shown to recruiters | O(V·E²); O(F·E) for small flow F | O(V + E) |
| `flow.MinCostFlow` | Successive shortest paths: Bellman–Ford once, then Dijkstra on Johnson-reduced costs | 5 (CO4) | The saved seat assignment: most seats, then best total match | O(V·E) + O(F·E log V) | O(V + E) |
| `flow.SeatAssigner` | Builds the source → role → candidate → sink network; greedy baseline and warm start | 5 (CO4) | Assigning every seat across several roles | As above | O(V + E) |
| `cover.GreedySetCover` | Greedy weighted set cover, H(d)-approximate, bit sets | 5 (CO4) | Which code regions an Ownership Check asks about | O(k · m · u / 64) | O(m · u / 64) |
| `score.PercentileBands` | Four nested randomized selections | 6 (CO5) | "Top 1/5/10/25%" badges per skill | Expected O(n) | O(n) |
| `score.SkillScoring` | Noisy-OR over evidence with a 12-month half-life | | Skill score 0–100 and evidence level | O(e) for e pieces | O(1) |
| `score.MatchScoring` | Weighted match with must-have gate; closest one-skill fix | | Shortlist scores and near misses | O(r) for r requirements | O(r) |

`baseline.Baselines` holds the straightforward versions (naive scan, linear prefix scan, full
sort, BFS components, greedy seat assignment, exact set cover by branch-and-bound). They are the comparison points in benchmarks and the oracles in
randomized tests.

## Correctness tests

134 JUnit tests, at least four per class. Each class includes a boundary case (empty input,
single element, match at the very end, capacity growth) and invalid-input checks. Randomized
tests compare the suffix array, KMP, radix tree, Quickselect and union-find against the
baselines on hundreds of seeded random inputs. Max-flow is checked against brute-force minimum
cuts on random small graphs, seat assignment against brute force on random drives, and set
cover's H(d) bound against the exact optimum on 300 random instances.

| Test class | Tests |
|---|---:|
| `SuffixArrayTest` | 9 |
| `CorpusIndexTest` | 8 |
| `KmpTest` | 8 |
| `SecretScannerTest` | 11 |
| `RadixTreeTest` | 9 |
| `BloomFilterTest` | 8 |
| `RandomizedSelectTest` | 9 |
| `DisjointSetUnionTest` | 7 |
| `ThreadStitcherTest` | 6 |
| `HybridRankerTest` | 8 |
| `MaxFlowTest` | 6 |
| `MinCostFlowTest` | 4 |
| `SeatAssignerTest` | 11 |
| `GreedySetCoverTest` | 6 |
| `PercentileBandsTest` | 6 |
| `SkillScoringTest` | 8 |
| `MatchScoringTest` | 10 |
| **Total** | **134** |

## Benchmarks

`bench.BenchmarkRunner` compares each algorithm with its baseline at 1,000, 10,000 and
100,000 items on seeded synthetic developer-chat data, and prints Markdown tables. Results are
in [`../docs/benchmarks.md`](../docs/benchmarks.md). With `--json` it writes the same results
as JSON, which the web app's Lab page charts (`web/src/data/benchmarks.json`). Timings at the smallest size are noisy
because the JIT is still warming up on such short workloads.

## Known limitations

- **Index build time.** Building the suffix array for 100,000 chunks (about 22 million
  characters) takes roughly 15 seconds. Queries are then sub-millisecond. The API therefore
  keeps one index per project, rebuilds it in the background after an import batch, and keeps
  serving the previous index meanwhile. A later improvement is a small index for new chunks
  that is merged into the main one periodically.
- **Bloom filters cannot delete.** After a user deletes data, a removed chunk can still test
  as "possibly seen". The exact hash check then reports it absent, so re-imports still work.
  The filter is rebuilt from the database nightly.
- **Secret scanning is a screen.** Secrets in unknown formats with low entropy can pass.
- **Case-insensitive matching** lower-cases one character at a time to keep offsets aligned,
  so a few special cases of full Unicode case folding are not handled.
