"use client";

import { Highlighted } from "@/components/Highlighted";
import { toWords } from "@/lib/highlight";
import { plural, ROLE_LABEL, shortDate } from "@/lib/format";
import type { SearchHit, SearchResponse, Why } from "@/lib/types";
import styles from "./recall.module.css";

const SNIPPET = 300;

const SIGNALS: { key: keyof Why; label: string; explain: string }[] = [
  { key: "exact", label: "Exact words", explain: "the passage contains the words you typed (suffix-array search)" },
  { key: "semantic", label: "Similar meaning", explain: "close in meaning to your query (vector search)" },
  { key: "recency", label: "Recent", explain: "how recently it was said; halves every two weeks" },
  { key: "decision", label: "Decision", explain: "the passage records a decision in force" },
];

/** Display names for the tools the API expands broad words into. */
const TOOL_NAMES: Record<string, string> = {
  postgre: "Postgres", mysql: "MySQL", mongo: "MongoDB", sqlite: "SQLite", dynamo: "DynamoDB", supabase: "Supabase",
  neon: "Neon", redis: "Redis", memcache: "Memcached", cdn: "CDN", jwt: "JWT", session: "sessions", oauth: "OAuth",
  login: "login", cookie: "cookies", kafka: "Kafka", rabbitmq: "RabbitMQ", sqs: "SQS", worker: "workers",
  vercel: "Vercel", docker: "Docker", kubernetes: "Kubernetes", "cloud run": "Cloud Run", "fly.io": "Fly.io",
  react: "React", "next.js": "Next.js", vue: "Vue", svelte: "Svelte", tailwind: "Tailwind", stripe: "Stripe",
  invoice: "invoices", billing: "billing", netlify: "Netlify", aws: "AWS", gcp: "GCP",
};

function interpretation(r: SearchResponse): string | null {
  const bits: string[] = [];
  const words = new Set(r.query.toLowerCase().split(/[^\p{L}\p{N}_./:#+-]+/u));
  // A term that isn't a whole word from the query is a stem: show it as a prefix ("cach…").
  const typed = [...r.phrases.map((p) => `"${p}"`), ...r.terms.map((t) => (words.has(t) ? `"${t}"` : `"${t}…"`))];
  if (typed.length) bits.push(`Matching ${typed.join(", ")}`);
  if (r.expansions.length) bits.push(`also ${r.expansions.map((e) => TOOL_NAMES[e] ?? e).join(", ")}`);
  if (r.decisionIntent) bits.push("decisions count for more");
  if (r.since) bits.push(`since ${shortDate(r.since)}`);
  return bits.length ? bits.join("; ") + "." : null;
}

export function Results({ results, searching }: { results: SearchResponse; searching: boolean }) {
  const reading = interpretation(results);
  return (
    <section className={styles.results} aria-label="Results" aria-busy={searching}>
      <div className={styles.resultsHead}>
        <p>
          <strong>{plural(results.hits.length, "result")}</strong>
          <span className="muted"> in {results.tookMillis} ms</span>
        </p>
        {reading ? <p className={styles.reading}>{reading}</p> : null}
      </div>
      {results.hits.length === 0 ? (
        <p className={styles.noResults}>
          No passages match. Try fewer or different words, or a name from the code, like a file or an error.
        </p>
      ) : (
        <ol className={styles.hits}>
          {results.hits.map((h) => (
            <Hit key={h.source.chunkId} hit={h} />
          ))}
        </ol>
      )}
    </section>
  );
}

/** The main reason a passage came up, in plain words, so nobody has to open the details to know. */
function reason(why: Why): string {
  if (why.exact > 0 && why.exact >= why.semantic) return "Exact match";
  if (why.semantic > 0) return why.exact > 0 ? "Similar meaning and some exact words" : "Similar meaning";
  if (why.decision > 0) return "A decision in force";
  return "Recent";
}

function reasonHelp(why: Why): string {
  if (why.exact > 0 && why.exact >= why.semantic) return "Contains exactly what you typed, found in the project's text index.";
  if (why.semantic > 0) return "Doesn't use your exact words but means something close.";
  return "Ranked by how recent it is.";
}

function Hit({ hit }: { hit: SearchHit }) {
  const total = hit.why.exact + hit.why.semantic + hit.why.recency + hit.why.decision;
  return (
    <li className={styles.hit}>
      <div className={styles.hitMeta}>
        <span className={styles.hitTitle}>{hit.source.conversationTitle}</span>
        <span>{shortDate(hit.source.sentAt)}</span>
        {hit.decision ? <span className={styles.decisionTag}>Decision</span> : null}
        <span className={styles.reasonTag} title={reasonHelp(hit.why)}>{reason(hit.why)}</span>
      </div>
      <p className={styles.hitText}>
        <span className={styles.role}>{ROLE_LABEL[hit.source.role] ?? hit.source.role}: </span>
        <Highlighted text={hit.text} spans={toWords(hit.text, hit.highlights)} length={SNIPPET} />
      </p>
      <details className={styles.why}>
        <summary>Why this result</summary>
        <dl className={styles.signals}>
          {SIGNALS.map((s) => {
            const value = hit.why[s.key];
            return (
              <div key={s.key} className={styles.signal} title={s.explain}>
                <dt>{s.label}</dt>
                <dd>
                  <span className={styles.bar} aria-hidden="true">
                    <span style={{ width: `${Math.min(100, (value / Math.max(total, 0.0001)) * 100)}%` }} />
                  </span>
                  <span className={styles.value}>{value.toFixed(2)}</span>
                </dd>
              </div>
            );
          })}
        </dl>
        <p className={styles.whyNote}>
          Score {hit.score.toFixed(2)}: the sum of the four signals above, each already weighted.
        </p>
      </details>
    </li>
  );
}
