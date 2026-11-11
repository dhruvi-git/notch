import type { Metadata } from "next";
import Link from "next/link";
import { ApiError, api } from "@/lib/server-api";
import { fileHref, ROLE_LABEL } from "@/lib/code";
import { plural, relativeTime, shortDate } from "@/lib/format";
import type { CodeOverview } from "@/lib/types";
import { PathLabel } from "@/components/PathLabel";
import { UploadCode } from "./UploadCode";
import styles from "./explain.module.css";

export const metadata: Metadata = { title: "Explain" };

export default async function ExplainPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  let o: CodeOverview | null = null;
  try {
    o = await api<CodeOverview>(`/api/projects/${projectId}/code-map`);
  } catch (e) {
    if (!(e instanceof ApiError && e.code === "no_code_map")) throw e;
  }
  if (!o) return <Empty projectId={projectId} />;

  const languages = Object.entries(o.stats.languages).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([l]) => l);
  const pages = o.entryPoints.filter((e) => e.role === "PAGE");
  const apis = o.entryPoints.filter((e) => e.role !== "PAGE");
  const base = `/p/${projectId}`;

  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="explain-title">
        <h1 id="explain-title" className={styles.stageTitle}>
          How {o.label} works
        </h1>
        <p className={styles.stats}>
          {plural(o.stats.files, "file")}, {o.stats.codeFiles.toLocaleString("en")} of them code,{" "}
          {o.stats.lines.toLocaleString("en")} lines{languages.length ? ` in ${languages.join(", ")}` : ""}.
        </p>
        <p className={styles.mapMeta}>
          Mapped {relativeTime(o.createdAt).toLowerCase()} {o.source === "cli" ? "with notch map" : "from a zip"}. Notch keeps
          the map, never the code.
        </p>
        <div className={styles.stageActions}>
          <Link href={`${base}/explain/files`} className="btn btn-yellow">
            Browse every file
          </Link>
          <UploadCode projectId={projectId} compact />
        </div>
      </section>

      {o.conflicts.length > 0 ? (
        <section className={styles.conflicts} aria-labelledby="conflicts-title">
          <h2 id="conflicts-title">
            {o.conflicts.length === 1 ? "1 file goes against a decision" : `${o.conflicts.length} files go against decisions`}
          </h2>
          <ul>
            {o.conflicts.map((c) => (
              <li key={c.path + c.tool}>
                <Link href={fileHref(projectId, c.path)}>
                  <PathLabel path={c.path} />
                </Link>{" "}
                uses <strong>{c.tool}</strong>, which{c.decidedAt ? ` the ${shortDate(c.decidedAt)}` : " a"} decision ruled out:{" "}
                <span className={styles.quote}>&ldquo;{c.decisionTitle}&rdquo;</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {o.startHere.length > 0 ? (
        <section className={styles.section} aria-labelledby="start-title">
          <h2 id="start-title">Start here</h2>
          <p className={styles.sectionIntro}>The code most of the rest depends on. Read these first and the rest makes sense.</p>
          <ol className={styles.startList}>
            {o.startHere.map((f, i) => (
              <li key={f.path}>
                <Link href={fileHref(projectId, f.path)} className={styles.startCard}>
                  <span className={styles.startNumber}>{i + 1}</span>
                  <span className={styles.startBody}>
                    <PathLabel path={f.path} />
                    <span className={styles.startSummary}>{f.summary || ROLE_LABEL[f.role]}</span>
                    <span className={styles.startMeta}>
                      Used by {plural(f.importedBy, "file")}
                      {f.conflict ? <span className="chip chip-warn">Goes against a decision</span> : null}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {o.entryPoints.length > 0 ? (
        <section className={styles.section} aria-labelledby="entry-title">
          <h2 id="entry-title">Where it starts</h2>
          <p className={styles.sectionIntro}>The pages people open and the endpoints other systems call.</p>
          <div className={styles.entryGrid}>
            {pages.length > 0 ? (
              <div className={styles.entryCol}>
                <h3>Pages</h3>
                <ul>
                  {pages.map((e) => (
                    <li key={e.path}>
                      <Link href={fileHref(projectId, e.path)}>
                        <code>{e.route}</code>
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            {apis.length > 0 ? (
              <div className={styles.entryCol}>
                <h3>API and entry points</h3>
                <ul>
                  {apis.map((e) => (
                    <li key={e.path}>
                      <Link href={fileHref(projectId, e.path)}>
                        {e.endpoints.length ? <code>{e.endpoints.join(", ")}</code> : <PathLabel path={e.path} />}
                      </Link>
                      <span className={styles.entryRole}>{ROLE_LABEL[e.role]}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        </section>
      ) : null}

      {o.tools.length > 0 ? (
        <section className={styles.section} aria-labelledby="tools-title">
          <h2 id="tools-title">Tools it relies on</h2>
          <p className={styles.sectionIntro}>Each tool the code imports, and the decision behind it.</p>
          <ul className={styles.tools}>
            {o.tools.map((t) => (
              <li key={t.tool} className={styles.tool} data-kind={t.kind ?? "NONE"}>
                <span className={styles.toolName}>{t.tool}</span>
                <span className={styles.toolFiles}>{plural(t.files, "file")}</span>
                <span className={styles.toolWhy}>
                  {t.kind === "FOLLOWS" ? (
                    <>Chosen in &ldquo;{t.decisionTitle}&rdquo;</>
                  ) : t.kind === "GOES_AGAINST" ? (
                    <>Ruled out in &ldquo;{t.decisionTitle}&rdquo;</>
                  ) : (
                    <>
                      No decision yet. <Link href={`${base}/compare`}>Weigh it in Compare</Link>
                    </>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className={styles.section} aria-labelledby="modules-title">
        <h2 id="modules-title">How it&rsquo;s organised</h2>
        <p className={styles.sectionIntro}>
          {o.areas.length > 1
            ? `${o.areas.length} separate areas that share no imports, made of these folders:`
            : "The folders, and which ones depend on each other:"}
        </p>
        <div className={styles.modules}>
          <table>
            <thead>
              <tr>
                <th scope="col">Folder</th>
                <th scope="col">Files</th>
                <th scope="col">Lines</th>
                <th scope="col">Depends on</th>
              </tr>
            </thead>
            <tbody>
              {o.modules.map((m) => (
                <tr key={m.path}>
                  <td><code>{m.path}</code></td>
                  <td>{m.files}</td>
                  <td>{m.lines.toLocaleString("en")}</td>
                  <td className={styles.deps}>{m.dependsOn.length ? m.dependsOn.join(", ") : <span className="muted">nothing else</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {o.possiblyUnused.length > 0 ? (
        <section className={styles.section} aria-labelledby="unused-title">
          <h2 id="unused-title">Possibly unused</h2>
          <p className={styles.sectionIntro}>
            Nothing that runs reaches these files through imports. Check before deleting: code loaded by name or by a framework
            can look unused.
          </p>
          <ul className={styles.plainList}>
            {o.possiblyUnused.map((f) => (
              <li key={f.path}>
                <Link href={fileHref(projectId, f.path)}><PathLabel path={f.path} /></Link>
                <span className="muted"> {f.summary}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {o.stats.filesWithSecrets.length > 0 ? (
        <section className={styles.section} aria-labelledby="secrets-title">
          <h2 id="secrets-title">Files that look like they hold secrets</h2>
          <p className={styles.sectionIntro}>Notch recorded only these paths, never the values. Check they aren&rsquo;t committed.</p>
          <ul className={styles.plainList}>
            {o.stats.filesWithSecrets.map((p) => (
              <li key={p}><PathLabel path={p} /></li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function Empty({ projectId }: { projectId: string }) {
  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="explain-title">
        <h1 id="explain-title" className={styles.stageTitle}>
          Understand the code you shipped
        </h1>
        <p className={styles.stats}>
          Notch maps your codebase: what each file does, how the pieces fit, where to start reading, and the decisions and
          chats behind each part.
        </p>
      </section>
      <div className={styles.emptyGrid}>
        <UploadCode projectId={projectId} />
        <section className={styles.cliCard} aria-labelledby="cli-title">
          <h2 id="cli-title">Or map it from your terminal</h2>
          <p>
            With the Notch CLI, the code is read on your computer and only the map is sent: paths, names and summaries, never
            code. It follows your <code>.gitignore</code>.
          </p>
          <pre className={styles.cmd}>{`notch link ${projectId}\nnotch map`}</pre>
          <p className="muted">
            Set up the CLI once with a token from Settings; see <code>cli/README.md</code>.
          </p>
        </section>
      </div>
    </div>
  );
}
