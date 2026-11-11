import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Highlighted } from "@/components/Highlighted";
import { PathLabel } from "@/components/PathLabel";
import { fileHref, ROLE_LABEL } from "@/lib/code";
import { plural, ROLE_LABEL as SPEAKER, shortDate } from "@/lib/format";
import { ApiError, api } from "@/lib/server-api";
import type { CodeFileView } from "@/lib/types";
import styles from "../explain.module.css";

export const metadata: Metadata = { title: "Explain a file" };

function spans(text: string, term: string): [number, number][] {
  const out: [number, number][] = [];
  const lower = text.toLowerCase();
  const t = term.toLowerCase();
  for (let i = lower.indexOf(t); i >= 0 && t; i = lower.indexOf(t, i + t.length)) out.push([i, i + t.length]);
  return out;
}

export default async function FilePage({
  params,
  searchParams,
}: {
  params: Promise<{ projectId: string }>;
  searchParams: Promise<{ path?: string }>;
}) {
  const { projectId } = await params;
  const { path } = await searchParams;
  if (!path) notFound();
  let v: CodeFileView;
  try {
    v = await api<CodeFileView>(`/api/projects/${projectId}/code-map/file?path=${encodeURIComponent(path)}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) notFound();
    throw e;
  }
  const f = v.file;
  const against = v.decisions.filter((d) => d.kind === "GOES_AGAINST");
  const follows = v.decisions.filter((d) => d.kind === "FOLLOWS");
  const base = `/p/${projectId}`;

  return (
    <div className={styles.page}>
      <p className={styles.back}>
        <Link href={`${base}/explain`}>How {v.mapLabel} works</Link>
      </p>
      <header className={styles.fileHead}>
        <h1 className={styles.filePath}>
          <PathLabel path={f.path} />
        </h1>
        <p className={styles.fileChips}>
          <span className="chip">{ROLE_LABEL[f.role]}</span>
          {f.language ? <span className={styles.fileFact}>{f.language}</span> : null}
          <span className={styles.fileFact}>{plural(f.lines, "line")}</span>
          {f.route ? <code className={styles.fileFact}>{f.route}</code> : null}
        </p>
      </header>

      {against.length > 0 ? (
        <p className={styles.againstBanner} role="status">
          This file uses {against.map((d) => d.tool).join(" and ")}, which{" "}
          {against.length === 1 && against[0].decidedAt ? `the ${shortDate(against[0].decidedAt)} decision` : "a decision"} ruled
          out. Either move it to what was decided, or, if {against.length === 1 ? `${against[0].tool} is` : "they're"} now
          intended,{" "}
          <Link href={`${base}/ledger`}>replace the decision in the Ledger</Link>.
        </p>
      ) : null}

      <section className={styles.what} aria-labelledby="what-title">
        <h2 id="what-title" className={styles.kicker}>What it does</h2>
        <p className={styles.whatText}>{f.summary || "No description yet: the file has no doc comment and nothing that names its job."}</p>
        {f.endpoints.length > 0 ? (
          <ul className={styles.endpoints}>
            {f.endpoints.map((e) => (
              <li key={e}><code>{e}</code></li>
            ))}
          </ul>
        ) : null}
        {f.symbols.length > 0 ? (
          <p className={styles.symbols}>
            <span className="muted">Exports </span>
            {f.symbols.slice(0, 12).map((s) => (
              <code key={s} className={styles.symbol}>{s}</code>
            ))}
            {f.symbols.length > 12 ? <span className="muted"> and {f.symbols.length - 12} more</span> : null}
          </p>
        ) : null}
      </section>

      <div className={styles.columns}>
        <section className={styles.card} aria-labelledby="how-title">
          <h2 id="how-title" className={styles.kicker}>How it fits</h2>
          <FileList title="Depends on" empty="No other files in this codebase." projectId={projectId} paths={f.imports} />
          <FileList title="Used by" empty="Nothing imports it." projectId={projectId} paths={f.importedBy} />
          {v.affected.length > f.importedBy.length ? (
            <details className={styles.affected}>
              <summary>Changing it could affect {plural(v.affected.length, "file")}</summary>
              <FileList title="" empty="" projectId={projectId} paths={v.affected} />
            </details>
          ) : v.affected.length > 0 ? (
            <p className={styles.affectedNote}>Changing it could affect {plural(v.affected.length, "file")}, all listed above.</p>
          ) : null}
          {f.packages.length > 0 ? (
            <div className={styles.listBlock}>
              <h3>Outside packages</h3>
              <p className={styles.packages}>
                {f.packages.map((p) => (
                  <code key={p}>{p}</code>
                ))}
              </p>
            </div>
          ) : null}
        </section>

        <section className={styles.card} aria-labelledby="why-title">
          <h2 id="why-title" className={styles.kicker}>Why it&rsquo;s like this</h2>
          {v.decisions.length === 0 && v.mentions.length === 0 ? (
            <p className="muted">
              No decision or conversation in Notch mentions this file or the tools it uses yet. Importing the chats where it was
              written would fill this in.
            </p>
          ) : null}
          {[...against, ...follows].length > 0 ? (
            <ul className={styles.why}>
              {[...against, ...follows].map((d) => (
                <li key={d.decisionId + d.tool} data-kind={d.kind}>
                  <span className={d.kind === "GOES_AGAINST" ? "chip chip-warn" : "chip chip-ok"}>
                    {d.kind === "GOES_AGAINST" ? `Goes against: ${d.tool}` : `Follows: ${d.tool}`}
                  </span>
                  <p>&ldquo;{d.decisionTitle}&rdquo;</p>
                  <p className={styles.whyMeta}>
                    Decided{d.decidedAt ? ` ${shortDate(d.decidedAt)}` : ""}. <Link href={`${base}/ledger`}>See it in the Ledger</Link>
                  </p>
                </li>
              ))}
            </ul>
          ) : null}
          {v.mentions.length > 0 ? (
            <div className={styles.listBlock}>
              <h3>Discussed in</h3>
              <ul className={styles.mentions}>
                {v.mentions.map((m, i) => (
                  <li key={i}>
                    <p className={styles.mentionText}>
                      <span className={styles.speaker}>{SPEAKER[m.role] ?? m.role}: </span>
                      <Highlighted text={m.text} spans={spans(m.text, m.matched)} length={320} />
                    </p>
                    {m.replyText ? (
                      <p className={styles.reply}>
                        <span className={styles.speaker}>{SPEAKER[m.replyRole ?? ""] ?? m.replyRole} replied: </span>
                        {m.replyText}
                      </p>
                    ) : null}
                    <p className={styles.whyMeta}>
                      {m.conversationTitle}, {shortDate(m.sentAt)}
                      {m.matched.endsWith("/") ? <>, about the <code>{m.matched}</code> folder</> : null}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </section>
      </div>
    </div>
  );
}

function FileList({ title, empty, projectId, paths }: { title: string; empty: string; projectId: string; paths: string[] }) {
  return (
    <div className={styles.listBlock}>
      {title ? <h3>{title}</h3> : null}
      {paths.length === 0 ? (
        empty ? <p className="muted">{empty}</p> : null
      ) : (
        <ul className={styles.fileLinks}>
          {paths.map((p) => (
            <li key={p}>
              <Link href={fileHref(projectId, p)}><PathLabel path={p} /></Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
