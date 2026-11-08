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

