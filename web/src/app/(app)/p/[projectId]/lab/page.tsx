import type { Metadata } from "next";
import bench from "@/data/benchmarks.json";
import { api } from "@/lib/server-api";
import { plural, relativeTime } from "@/lib/format";
import type { ImportReport, IndexInfo, Thread } from "@/lib/types";
import { Compare } from "./Compare";
import styles from "./lab.module.css";

export const metadata: Metadata = { title: "Lab" };

const SIZE = (n: number, noun: string) => `${n.toLocaleString("en")} ${noun}`;

function kb(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
}

export default async function Lab({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const [index, threads, imports] = await Promise.all([
    api<IndexInfo>(`/api/projects/${projectId}/index`),
    api<Thread[]>(`/api/projects/${projectId}/threads`),
    api<ImportReport[]>(`/api/projects/${projectId}/imports`),
  ]);
  const flagged = imports.reduce((s, r) => s + r.duplicateChunks + r.bloomFalsePositives, 0);
  const confirmed = imports.reduce((s, r) => s + r.duplicateChunks, 0);
  const falseAlarms = imports.reduce((s, r) => s + r.bloomFalsePositives, 0);
  const stored = imports.reduce((s, r) => s + r.chunksAdded, 0);
  const s = index.stats;
  const big = bench.exactSearch[bench.exactSearch.length - 1];
  const bloomBig = bench.bloom[bench.bloom.length - 1];

  return (
    <div className={styles.page}>
      <h1>Lab</h1>
      <p className={styles.intro}>
        How Notch&rsquo;s algorithms behave: in this project right now, and on benchmark data at three sizes. Every
        algorithm here is written from scratch in Java; none of the work is handed to a library.
      </p>

      <section className={styles.section} aria-labelledby="this-project">
        <h2 id="this-project">This project</h2>
        <dl className={styles.facts}>
          <div>
            <dt>Search index</dt>
            <dd>
              {plural(s.chunks, "passage")} from {plural(s.conversations, "conversation")}, {s.indexedChars.toLocaleString("en")}{" "}
              characters, built in {s.buildMillis} ms, {relativeTime(s.builtAt).toLowerCase()}
            </dd>
          </div>
          <div>
            <dt>Autocomplete</dt>
            <dd>{plural(s.autocompleteKeys, "key")} in the radix tree: files, identifiers, errors and chat titles</dd>
          </div>
          <div>
            <dt>Threads</dt>
            <dd>
              {threads.length === 0
                ? "No conversations share a file or error yet"
                : `${plural(threads.length, "thread")} linking ${plural(threads.reduce((n, t) => n + t.conversations.length, 0), "conversation")} that share files or errors`}
            </dd>
          </div>
          <div>
            <dt>Repeat check</dt>
            <dd>
              {flagged === 0
                ? `No passage has looked like a repeat yet; ${plural(stored, "passage")} stored`
                : `The Bloom filter flagged ${plural(flagged, "passage")}; the exact check confirmed ${plural(confirmed, "repeat")} and cleared ${plural(falseAlarms, "false alarm")}. ${plural(stored, "passage")} stored.`}
            </dd>
          </div>
        </dl>
      </section>

      <section className={styles.section} aria-labelledby="benchmarks">
        <h2 id="benchmarks">Benchmarks</h2>
        <p className={styles.method}>
          Each algorithm against the simple approach it replaces, on seeded synthetic developer chats. Median of{" "}
          {bench.runs} timed runs after {bench.warmup} warm-up runs, Java {bench.java}. Bars use a log scale.
        </p>

        <article className={styles.bench}>
          <h3>Exact search</h3>
          <p className={styles.what}>
            Finds error codes, function names and file paths in every conversation. <strong>Suffix array</strong>{" "}
            (Unit 4): O(m log n) per query after an O(n log n) build. 200 queries per run.
          </p>
          <Compare unit="ms" fastLabel="Suffix array" slowLabel="Scan every passage"
            rows={bench.exactSearch.map((r) => ({ size: SIZE(r.n, "passages"), fast: r.fastMs, slow: r.slowMs }))} />
          <p className={styles.caveat}>
            The trade-off: building the index for {big.n.toLocaleString("en")} passages takes{" "}
            {(big.buildMs / 1000).toFixed(1)} s. Notch rebuilds it in the background after imports and keeps answering from
            the previous one meanwhile.
          </p>
        </article>

        <article className={styles.bench}>
          <h3>Autocomplete</h3>
          <p className={styles.what}>
            Suggests files, errors and chats as you type <kbd>@</kbd>. <strong>Radix tree</strong> (Unit 4) with
            randomized selection of the top 10. 1,000 lookups per run.
          </p>
          <Compare unit="ms" fastLabel="Radix tree" slowLabel="Check every key"
            rows={bench.autocomplete.map((r) => ({ size: SIZE(r.n, "keys"), fast: r.fastMs, slow: r.slowMs }))} />
        </article>

        <article className={styles.bench}>
          <h3>Picking the best results</h3>
          <p className={styles.what}>
            Chooses the top 20 passages for an answer. <strong>Randomized Quickselect</strong> (Unit 6): expected
            O(n + k log k) instead of sorting everything. 50 selections per run.
          </p>
          <Compare unit="ms" fastLabel="Quickselect" slowLabel="Full sort"
            rows={bench.topK.map((r) => ({ size: SIZE(r.n, "scores"), fast: r.fastMs, slow: r.slowMs }))} />
        </article>

        <article className={styles.bench}>
          <h3>Linking conversations into threads</h3>
          <p className={styles.what}>
            Adds a new conversation to the threads it shares files or errors with. <strong>Union-find</strong> (Unit 2):
            near-constant time per link, against recomputing every thread with a graph search.
          </p>
          <Compare unit="µs" fastLabel="Union-find, per insert" slowLabel="Recompute, per insert"
            rows={bench.threading.map((r) => ({ size: SIZE(r.n, "chats"), fast: r.fastUs, slow: r.slowUs }))} />
          <p className={styles.caveat}>
            A union-find link takes well under a microsecond, close to what the timer can resolve, so read these ratios as
            the scale of the difference rather than exact figures.
          </p>
        </article>

        <article className={styles.bench}>
          <h3>Repeat check</h3>
          <p className={styles.what}>
            Skips passages already stored, before they&rsquo;re embedded. <strong>Bloom filter</strong> (Unit 6, CO5):
            &ldquo;definitely new&rdquo; or &ldquo;possibly seen&rdquo;, every &ldquo;possibly&rdquo; confirmed exactly.
            Sized for a 1% false-alarm rate.
          </p>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col">Passages</th>
                <th scope="col">Expected false alarms</th>
                <th scope="col">Measured</th>
                <th scope="col">Filter size</th>
                <th scope="col">Storing every hash</th>
              </tr>
            </thead>
            <tbody>
              {bench.bloom.map((r) => (
                <tr key={r.n}>
                  <td>{r.n.toLocaleString("en")}</td>
                  <td>{(r.theoreticalFpr * 100).toFixed(2)}%</td>
                  <td>{(r.measuredFpr * 100).toFixed(2)}%</td>
                  <td>{kb(r.memoryBytes)}</td>
                  <td>{kb(r.rawBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className={styles.caveat}>
            Measured against 200,000 passages never added. At {bloomBig.n.toLocaleString("en")} passages the filter uses{" "}
            {kb(bloomBig.memoryBytes)} where storing the hashes would take {kb(bloomBig.rawBytes)}.
          </p>
        </article>
        <p className={styles.method}>
          Reproduce: <code>java -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner</code>. Last run{" "}
          {new Date(bench.generatedAt).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" })}.
        </p>
      </section>
    </div>
  );
}
