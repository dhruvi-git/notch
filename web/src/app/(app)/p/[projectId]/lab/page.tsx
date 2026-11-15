import type { Metadata } from "next";
import Link from "next/link";
import bench from "@/data/benchmarks.json";
import { ApiError, api } from "@/lib/server-api";
import { plural, relativeTime } from "@/lib/format";
import type { CodeOverview, ImportReport, IndexInfo, Thread } from "@/lib/types";
import { Compare } from "./Compare";
import styles from "./lab.module.css";

export const metadata: Metadata = { title: "Under the hood" };

const SIZE = (n: number, noun: string) => `${n.toLocaleString("en")} ${noun}`;

function kb(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
}

/**
 * Under the hood: each thing Notch does for the person, the algorithm that makes it work, what
 * it's doing in this project right now, and how it compares with the simple approach.
 */
export default async function UnderTheHood({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const p = `/api/projects/${projectId}`;
  const [index, threads, imports, map] = await Promise.all([
    api<IndexInfo>(`${p}/index`),
    api<Thread[]>(`${p}/threads`),
    api<ImportReport[]>(`${p}/imports`),
    api<CodeOverview>(`${p}/code-map`).catch((e) => {
      if (e instanceof ApiError) return null;
      throw e;
    }),
  ]);
  const flagged = imports.reduce((s, r) => s + r.duplicateChunks + r.bloomFalsePositives, 0);
  const confirmed = imports.reduce((s, r) => s + r.duplicateChunks, 0);
  const falseAlarms = imports.reduce((s, r) => s + r.bloomFalsePositives, 0);
  const stored = imports.reduce((s, r) => s + r.chunksAdded, 0);
  const secrets = imports.reduce((s, r) => s + r.secretsRedacted, 0);
  const linked = threads.reduce((n, t) => n + t.conversations.length, 0);
  const s = index.stats;
  const big = bench.exactSearch[bench.exactSearch.length - 1];
  const bloomBig = bench.bloom[bench.bloom.length - 1];
  const base = `/p/${projectId}`;

  return (
    <div className={styles.page}>
      <h1>Under the hood</h1>
      <p className={styles.intro}>
        Each thing Notch does rests on an algorithm written from scratch in Java; none of the work is handed to a library.
        For each one: the problem it solves for you, what it&rsquo;s doing in this project right now, and how much faster or
        smaller it is than the simple approach, measured at three sizes.
      </p>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Finding an exact error, file or name</h2>
        <p className={styles.usedIn}>
          Used in <Link href={`${base}/recall`}>Ask</Link>, in each <Link href={`${base}/review`}>review</Link> issue&rsquo;s
          &ldquo;seen before&rdquo;, and by <code>notch why</code>.
        </p>
        <p className={styles.what}>
          Meaning-based search is poor at exact strings: <code>ECONNREFUSED</code> or <code>src/db/pool.ts</code> must match
          exactly, wherever it was said. A <strong>suffix array</strong> (Unit 4) over every passage finds every occurrence in
          O(m log n) per query after an O(n log n) build.
        </p>
        <p className={styles.live}>
          In this project: {plural(s.chunks, "passage")} from {plural(s.conversations, "conversation")},{" "}
          {s.indexedChars.toLocaleString("en")} characters, indexed in {s.buildMillis} ms {relativeTime(s.builtAt).toLowerCase()}.
        </p>
        <Compare unit="ms" fastLabel="Suffix array" slowLabel="Scan every passage"
          rows={bench.exactSearch.map((r) => ({ size: SIZE(r.n, "passages"), fast: r.fastMs, slow: r.slowMs }))} />
        <p className={styles.caveat}>
          The trade-off is the build: {(big.buildMs / 1000).toFixed(1)} s for {big.n.toLocaleString("en")} passages. Notch
          rebuilds in the background after new captures and answers from the previous index meanwhile.
        </p>
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Not storing the same thing twice</h2>
        <p className={styles.usedIn}>
          Used by <Link href={`${base}/imports`}>Upload</Link> and by automatic capture after every commit and session.
        </p>
        <p className={styles.what}>
          Automatic capture sends the same session again and again as it grows, so most passages have been seen before. A{" "}
          <strong>Bloom filter</strong> (Unit 6, CO5) answers &ldquo;definitely new&rdquo; or &ldquo;possibly seen&rdquo; in
          O(k) without asking the database; every &ldquo;possibly&rdquo; is confirmed exactly. Repeats never reach the
          embedding model, which saves time and money.
        </p>
        <p className={styles.live}>
          In this project:{" "}
          {flagged === 0
            ? `nothing has looked like a repeat yet; ${plural(stored, "passage")} stored.`
            : `${plural(flagged, "passage")} flagged as possibly seen; ${plural(confirmed, "repeat")} confirmed and skipped, ${plural(falseAlarms, "false alarm")} cleared and stored; ${plural(stored, "passage")} stored in all.`}
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
          Measured against 200,000 passages never added. At {bloomBig.n.toLocaleString("en")} passages the filter takes{" "}
          {kb(bloomBig.memoryBytes)} where keeping the hashes would take {kb(bloomBig.rawBytes)}.
        </p>
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Grouping sessions about the same work</h2>
        <p className={styles.usedIn}>
          Used for &ldquo;related sessions&rdquo; in <Link href={`${base}/review`}>Review</Link>, and for the connected areas
          in <Link href={`${base}/explain`}>Explain</Link>.
        </p>
        <p className={styles.what}>
          Sessions that mention the same file or error are about the same piece of work, even days apart. Each new capture is
          joined to its group as it arrives with <strong>union-find</strong> (Unit 2), in near-constant time, instead of
          recomputing every group with a graph search. The same structure finds which parts of the code are connected by
          imports.
        </p>
        <p className={styles.live}>
          In this project:{" "}
          {threads.length === 0
            ? "no two conversations share a file or error yet."
            : `${plural(threads.length, "group")} linking ${plural(linked, "conversation")}.`}
          {map ? ` The code falls into ${plural(map.areas.length, "connected area")}.` : ""}
        </p>
        <Compare unit="µs" fastLabel="Union-find, per new session" slowLabel="Recompute, per new session"
          rows={bench.threading.map((r) => ({ size: SIZE(r.n, "sessions"), fast: r.fastUs, slow: r.slowUs }))} />
        <p className={styles.caveat}>
          A union-find link takes well under a microsecond, close to what the timer resolves, so read the ratios as the scale
          of the difference rather than exact figures.
        </p>
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Choosing the best few out of many</h2>
        <p className={styles.usedIn}>
          Used for Ask&rsquo;s top results, Explain&rsquo;s &ldquo;start here&rdquo; files, the files a review reads, and the
          order of tasks in <Link href={`${base}/resume`}>Where you stopped</Link>.
        </p>
        <p className={styles.what}>
          Notch often needs the top 10 or 20 of thousands: passages, files, tasks. <strong>Randomized Quickselect</strong>{" "}
          (Unit 6) finds them in expected O(n + k log k) instead of sorting everything.
        </p>
        <p className={styles.live}>
          In this project:{" "}
          {map
            ? `${plural(map.startHere.length, "start-here file")} chosen from ${plural(map.stats.files, "file")}.`
            : "add your code and Explain picks its start-here files this way."}
        </p>
        <Compare unit="ms" fastLabel="Quickselect" slowLabel="Full sort"
          rows={bench.topK.map((r) => ({ size: SIZE(r.n, "scores"), fast: r.fastMs, slow: r.slowMs }))} />
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Suggesting files and names as you type</h2>
        <p className={styles.usedIn}>
          Used when you type <kbd>@</kbd> in <Link href={`${base}/recall`}>Ask</Link>.
        </p>
        <p className={styles.what}>
          A <strong>radix tree</strong> (Unit 4) holds every file path and name, error and conversation title, sharing common
          prefixes, so a suggestion costs the length of what you typed, not the number of keys.
        </p>
        <p className={styles.live}>
          In this project: {plural(s.autocompleteKeys, "name")} from conversations
          {map ? ` and ${plural(map.stats.files, "file")} from the code` : ""}.
        </p>
        <Compare unit="ms" fastLabel="Radix tree" slowLabel="Check every key"
          rows={bench.autocomplete.map((r) => ({ size: SIZE(r.n, "keys"), fast: r.fastMs, slow: r.slowMs }))} />
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Keeping secrets out, and stopping ruled-out tools</h2>
        <p className={styles.usedIn}>
          Used on every upload and capture, before Claude reads code for a review, and by the commit guard.
        </p>
        <p className={styles.what}>
          <strong>KMP</strong> (Unit 4) matches known patterns in one linear pass: key formats like <code>sk-ant-</code> and{" "}
          <code>ghp_</code> (with a Shannon-entropy check for unknown ones), and the packages a decision ruled out, in large
          diffs and transcripts. It also highlights the exact sentence an answer quotes.
        </p>
        <p className={styles.live}>
          In this project: {plural(secrets, "secret")} removed before anything was stored.
        </p>
      </article>

      <article className={styles.bench}>
        <h2 className={styles.feature}>Ranking results and saying why</h2>
        <p className={styles.usedIn}>
          Used by <Link href={`${base}/recall`}>Ask</Link>: each result says whether it was an exact match or similar in meaning.
        </p>
        <p className={styles.what}>
          The <strong>hybrid ranker</strong> (Unit 6) combines exact matches, similar meaning, recency and decisions into one
          score per passage, keeps each signal&rsquo;s share so the result can explain itself, and takes the top results with
          Quickselect.
        </p>
      </article>

      <p className={styles.method}>
        Benchmarks: each algorithm against the simple approach it replaces, on seeded synthetic developer data; median of{" "}
        {bench.runs} timed runs after {bench.warmup} warm-up runs, Java {bench.java}. Bars use a log scale. Reproduce with{" "}
        <code>java -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner</code>. Last run{" "}
        {new Date(bench.generatedAt).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" })}.
      </p>
    </div>
  );
}
