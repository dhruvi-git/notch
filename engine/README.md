# Notch algorithm engine

Every assessed algorithm in Notch lives here, written from scratch in Java 21. The module has
no runtime dependencies; JUnit 5 is used for tests only. Spring Boot, Postgres and pgvector
in the rest of the project provide infrastructure and never replace these classes.

## Algorithms

| Class | Technique | Unit | Used for | Time | Space |
|---|---|---|---|---|---|
| `text.SuffixArray` | Suffix array, prefix doubling with counting sort | 4 | Exact search over all conversations | Build O(n log n), query O(m log n) | O(n) |
| `text.CorpusIndex` | Suffix array over joined documents | 4 | Error codes, identifiers, paths; per-document offsets | As above, plus O(log d) per hit to find its document | O(n) |
| `text.Kmp` | Knuth–Morris–Pratt | 4 | Highlighting cited spans; secret detection | O(n + m) | O(m) |
| `text.SecretScanner` | KMP on key prefixes + Shannon entropy | 4 | Redacting credentials before storage | O(n · r) for r rules | O(n) |
| `text.RadixTree` | Compressed trie with weighted keys | 4 | `@` autocomplete for projects, files, symbols | Insert/lookup O(L); complete O(p + s) expected for s matches | O(total key length) |
| `probabilistic.BloomFilter` | Bloom filter, double hashing | 6 (CO5) | Skipping duplicate chunks before embedding | O(k) per add/check | m bits |
| `select.RandomizedSelect` | Randomized Quickselect, 3-way partition | 6 (CO5) | Top-k results; context-budget selection; percentiles | Expected O(n + k log k) | O(n) copy |
| `graph.DisjointSetUnion` | Union by rank + path halving | 2 | Thread stitching | O(α(n)) amortized | O(n) |
| `graph.ThreadStitcher` | Union-find over shared link keys | 2 | Grouping conversations about the same work | O(keys · α(n)) per conversation | O(n + keys) |
| `rank.HybridRanker` | Weighted signal fusion + Quickselect | 6 | Final ranking with per-signal explanations | Expected O(n + k log k) | O(n) |

`baseline.Baselines` holds the straightforward versions (naive scan, linear prefix scan, full
sort, BFS components). They are the comparison points in benchmarks and the oracles in
randomized tests.

## Correctness tests

81 JUnit tests, at least six per algorithm. Each class includes a boundary case (empty input,
single element, match at the very end, capacity growth) and invalid-input checks. Randomized
tests compare the suffix array, KMP, radix tree, Quickselect and union-find against the
baselines on hundreds of seeded random inputs.

| Test class | Tests |
|---|---:|
| `SuffixArrayTest` | 9 |
| `CorpusIndexTest` | 8 |
| `KmpTest` | 8 |
| `SecretScannerTest` | 9 |
| `RadixTreeTest` | 9 |
| `BloomFilterTest` | 8 |
| `RandomizedSelectTest` | 9 |
| `DisjointSetUnionTest` | 7 |
| `ThreadStitcherTest` | 6 |
| `HybridRankerTest` | 8 |

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
