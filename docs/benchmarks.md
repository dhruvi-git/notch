# Notch engine benchmarks
Java 21.0.12.1, 2 warm-up + 5 timed runs, median reported, seed 2026

## Exact search: suffix array vs. scanning every chunk
200 queries per run. Chunks average about 200 characters.

| Chunks | Index build (ms) | Suffix array (ms) | Naive scan (ms) | Speed-up |
|---:|---:|---:|---:|---:|
| 1,000 | 70.6 | 0.52 | 12.5 | 24x |
| 10,000 | 597.7 | 0.15 | 132.7 | 861x |
| 100,000 | 14602.6 | 0.19 | 1312.4 | 6914x |

## Autocomplete: radix tree vs. linear prefix scan
1,000 prefix queries per run, top 10 by weight.

| Keys | Radix tree (ms) | Linear scan (ms) | Speed-up | Nodes vs. trie chars |
|---:|---:|---:|---:|---:|
| 1,000 | 7.88 | 31.72 | 4.0x | 1,523 vs. 35,114 |
| 10,000 | 32.32 | 214.75 | 6.6x | 15,010 vs. 348,459 |
| 100,000 | 117.23 | 2529.99 | 21.6x | 141,821 vs. 3,528,611 |

## Top-k selection: randomized Quickselect vs. full sort
Select the 20 best of n fused scores, 50 times per run.

| Scores | Quickselect (ms) | Full sort (ms) | Speed-up |
|---:|---:|---:|---:|
| 1,000 | 1.53 | 16.44 | 10.8x |
| 10,000 | 16.05 | 80.14 | 5.0x |
| 100,000 | 86.52 | 1064.07 | 12.3x |

## Thread stitching: incremental union-find vs. BFS recompute
Cost of absorbing one new conversation once n already exist.

| Conversations | Union-find per insert (us) | BFS recompute per insert (us) | Speed-up |
|---:|---:|---:|---:|
| 1,000 | 0.074 | 842.9 | 11,328x |
| 10,000 | 0.074 | 4985.3 | 66,954x |
| 100,000 | 0.029 | 24286.4 | 830,447x |

## Bloom filter: measured vs. theoretical false-positive rate
Sized for 1% at n items; probed with 200,000 items never inserted.

| Items | Bits | Hashes | Theoretical FPR | Measured FPR | Filter memory | Raw 64-byte hashes |
|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 9,586 | 7 | 1.003% | 1.017% | 1.2 KB | 62.5 KB |
| 10,000 | 95,851 | 7 | 1.004% | 1.037% | 11.7 KB | 625.0 KB |
| 100,000 | 958,506 | 7 | 1.004% | 0.983% | 117.0 KB | 6.1 MB |


## Seat assignment: Edmonds–Karp max-flow vs. greedy
Synthetic campus drive: 5 roles with seats for 10%, 10%, 5%, 5% and 5% of the pool, threshold 70;
a fifth of candidates qualify for every role, the rest for one popular role. Seats filled is the quality measure.
Measured on Java 21.0.13 in a cloud workspace; the 100,000 row is the median of 3 runs (cold start: 1 run).

| Candidates | Seats | Greedy filled | Max-flow filled | Greedy (ms) | Max-flow, cold start (ms) | Max-flow, greedy start (ms) | Augmenting paths (cold / warm) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 350 | 297 | 350 | 1.9 | 4.0 | 2.5 | 350 / 53 |
| 10,000 | 3,500 | 2,824 | 3,500 | 9.4 | 151.2 | 61.9 | 3,500 / 676 |
| 100,000 | 35,000 | 28,301 | 35,000 | 48.8 | 28546.4 | 13550.0 | 35,000 / 6,699 |

Greedy leaves about 19% of seats empty because it spends all-rounders on the popular role, where
specialists would have done. Max-flow fills every seat. Starting from the greedy assignment cuts the
augmenting paths by about 80% and the run time by 50–60%, with the same result. Each path costs one
breadth-first search over the residual graph, O(E), so the warm start runs in O(E · (optimum − greedy)).
A drive of 10,000 candidates finishes in about 60 ms. At 100,000 the 13.5 s run is the practical
limit of Edmonds–Karp here; Dinic's algorithm, which finds many shortest paths per search, would be
the next step.

## Percentile bands: randomized Quickselect vs. full sort
The four badge cut-offs (99th, 95th, 90th, 75th percentile) of n skill scores. One copy of the
scores; each selection searches only the part at or below the previous cut-off.

| Scores | Quickselect (ms) | Full sort (ms) | Speed-up |
|---:|---:|---:|---:|
| 1,000 | 0.64 | 0.40 | 0.6x |
| 10,000 | 0.96 | 1.28 | 1.3x |
| 100,000 | 4.05 | 12.87 | 3.2x |
| 1,000,000 | 33.08 | 87.48 | 2.6x |

Below about 10,000 scores the JDK's tuned dual-pivot sort wins on constant factors; from there the
expected O(n) selection pulls ahead of O(n log n), as predicted.

## Ownership Check planning: greedy weighted set cover vs. exact search
What to ask in an Ownership Check: pick code regions (functions, queries, methods) so that the
questions touch every skill, concept and main file the repository shows, as cheaply as possible
(a region's cost grows with its length). Greedy takes the region with the best cost per newly
covered element each time; the exact baseline is branch-and-bound over every subset.
Small instances (elements / candidate regions) compare quality against the optimum; large ones
show the run time where exact search is out of reach.

| Elements | Regions | Greedy picks | Optimal picks | Ratio | Greedy (ms) | Exact (ms) |
|---:|---:|---:|---:|---:|---:|---:|
| 20 | 10 | 11 | 9 | 1.22 | 0.05 | 0.9 |
| 30 | 14 | 11 | 11 | 1.00 | 0.05 | 2.1 |
| 40 | 18 | 13 | 12 | 1.08 | 0.05 | 13.9 |
| 100 | 1,000 | 8 | — | — | 1.1 | — |
| 200 | 10,000 | 8 | — | — | 4.7 | — |
| 400 | 20,000 | 10 | — | — | 9.7 | — |

(Picks are weighted costs, rounded.) Greedy stays within 22% of the optimum on every small
instance, far inside its guarantee of H(d) ≤ 1 + ln d times optimal, where d is the most elements
any one region covers; the unit tests check that bound against brute force on 300 random instances.
Exact search grows exponentially (0.9 → 13.9 ms for 8 more regions), while greedy plans a check
over 20,000 regions in under 10 ms. A real repository gives 50 to 400 regions.

