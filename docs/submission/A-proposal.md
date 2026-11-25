# Notch: proof-based portfolios and seat assignment for campus hiring

**Project proposal** · CIA 3, Advanced Algorithms · Semester V, B.Tech CS (AI & ML), 2026–27

| Team member | Student ID | Role |
|---|---|---|
| Dhruvi Jain | 2400297 | Sole member: design, algorithms, backend, frontend, testing, report |

## 1. The real-world problem

Campus placement drives match hundreds of students to a handful of roles, and two things go wrong.

**Résumés are claims, not evidence.** "SQL, Python, Power BI" costs nothing to type. Recruiters
cannot tell a student who built three data pipelines from one who watched a tutorial, so they
fall back on CGPA and brand-name projects. Students with real but quiet work go unseen.

**Seats are filled greedily.** A drive with several roles is usually filled role by role or
"best scorer first". That spends versatile candidates on whichever popular role is processed
first, and seats in harder-to-fill roles stay empty even when a valid assignment exists to
fill them all. On a synthetic drive modelled on campus hiring, best-score-first leaves **about
19% of seats empty** (Section 6).

## 2. Target users

- **Students** (primarily final-year B.Tech) who want their real work, from GitHub repositories,
  projects and AI-assisted learning, turned into evidence a recruiter will trust.
- **Campus recruiters and placement cells** who run drives with several roles and need a ranked,
  explainable shortlist and an assignment that fills as many seats as possible.

## 3. Proposed features

1. **Evidence-backed skill profile.** Connect GitHub: the student's own commits, active weeks,
   languages and topics become per-skill scores (0–100) with an evidence level: *Claimed →
   Shown → Verified → Defended*.
2. **Ownership Check.** A timed, 5-question interview about the student's *own* code. Passing
   marks the repository **Defended**, the highest level, with a public transcript.
3. **Percentile badges.** "Top 10% in SQL", computed over every public candidate.
4. **Constellation, archetype and "Said vs done".** A visual read of what the student builds,
   next to what they ask AI about.
5. **Hiring drives.** A pasted job description becomes weighted requirements; each role gets a
   ranked shortlist with a per-skill breakdown and the "closest fix" for near misses.
6. **Seat assignment.** One click assigns every seat across all roles of a drive, shown side by
   side with the greedy result.
7. **Integrity screens.** A copied repository cannot verify skills for two people.

## 4. Two core techniques and why each fits

### Technique 1: Network flow (Unit 5, CO4): max-flow and min-cost max-flow

Seat assignment is a bipartite *b*-matching: roles have capacities (seats), candidates have
capacity 1, and an edge exists when a candidate meets a role's minimum match. Modelled as a flow
network (source → role → candidate → sink), **Edmonds–Karp max-flow** gives the largest
possible number of filled seats, and the **max-flow min-cut theorem** proves no assignment can do
better. **Min-cost max-flow** (successive shortest paths, Bellman–Ford then Dijkstra with Johnson
potentials, cost = −match score) then picks, among all maximum assignments, the one with the
highest total match.

*Why it is appropriate:* the problem is exactly a capacitated matching, integral capacities
guarantee an integral (valid) assignment, and the optimality certificate is a theorem rather
than a heuristic. Greedy has no such guarantee, which is the failure in Section 1.

### Technique 2: Randomized algorithms (Unit 6, CO5): randomized Quickselect and Bloom filters

- **Randomized Quickselect with three-way partitioning** finds percentile cut-offs (99th, 95th,
  90th, 75th) for every skill in **expected O(n)**, instead of O(n log n) for sorting, and
  returns top-k shortlists in expected O(n + k log k). Random pivots make the bound hold for any
  input order; three-way partitioning keeps it when many scores tie.
- A **Bloom filter** (double hashing) screens every repository fingerprint on the platform for
  copies in constant time and about 1.2 MB per million fingerprints. Its one-sided error ("definitely new" is
  always right) is backed by an exact database check, so it saves work without deciding anything wrong.

*Why it is appropriate:* percentiles are recomputed whenever the candidate pool changes, and the
pool can reach hundreds of thousands of score values. Selection does strictly less work than
sorting. The duplicate screen runs on every GitHub sync, where a probabilistic "no" avoids a
database lookup in the common case.

**Supporting techniques:** greedy weighted set cover (Ownership Check questions), radix tree,
suffix array, KMP and union-find.

## 5. Data sources

| Source | Used for |
|---|---|
| **GitHub REST API** (public data only) | Repositories, the student's own commit counts and weeks, languages, topics, first-commit and tree SHAs |
| **ChatGPT / Claude exports** and pasted **AI-memory replies** | Skills the student asked about or learned, approved by the student before they count |
| **Job descriptions** pasted by recruiters | Role requirements, weights and must-haves |
| **Synthetic seeded pool** (`/api/dev/demo-pool`, 300 candidates, same on every machine) | Reproducible demos and shortlists |
| **Seeded synthetic generators** (`engine/.../bench/Synthetic`) | Benchmarks at 1,000 to 1,000,000 items |
| **Sample files** in `samples/` | Representative test data: chat exports, notes, a sample codebase |

## 6. Measurable success criteria

| # | Criterion | Target |
|---|---|---|
| 1 | Seats filled by max-flow on the synthetic campus drive | 100% of fillable seats, and at least 15 percentage points more than best-score-first |
| 2 | Seat assignment time for a 10,000-candidate drive | Under 100 ms |
| 3 | Assignment optimality | Matches brute force on every randomized small drive in the tests; min-cost flow's total match ≥ greedy's whenever both fill the same seats |
| 4 | Percentile cut-offs | Identical to the full-sort cut-offs on every randomized test; faster than sorting from 100,000 scores |
| 5 | Bloom filter false-positive rate | Within ±0.1 percentage points of the 1% design target, in under 2% of the memory of raw hashes |
| 6 | Ownership Check planning | Greedy cost within H(d) × optimum on 300 random instances; a plan over 20,000 regions in under 50 ms |
| 7 | Correctness and reproducibility | Every algorithm unit-tested against a baseline or brute force; all tests pass in CI on every push |
| 8 | Product | A student goes from sign-in to a scored profile in under a minute; error responses are structured and never leak another user's data (cross-user isolation test) |
