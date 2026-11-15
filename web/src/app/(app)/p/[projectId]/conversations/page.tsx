import type { Metadata } from "next";
import Link from "next/link";
import { api } from "@/lib/server-api";
import { plural, shortDate, SOURCE_LABEL } from "@/lib/format";
import type { ConversationSummary } from "@/lib/types";
import styles from "./conversations.module.css";

export const metadata: Metadata = { title: "Conversations" };

export default async function Conversations({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const conversations = await api<ConversationSummary[]>(`/api/projects/${projectId}/conversations`);
  return (
    <div className={styles.page}>
      <h1>Conversations</h1>
      {conversations.length === 0 ? (
        <p className={styles.empty}>
          No conversations yet. Connect the repository to capture Claude Code sessions and commits automatically, or{" "}
          <Link href={`/p/${projectId}/imports`}>add a chat or notes</Link> (optional).
        </p>
      ) : (
        <>
          <p className={styles.intro}>
            {plural(conversations.length, "conversation")} in this project, newest first.
          </p>
          <ul className={styles.list}>
            {conversations.map((c) => (
              <li key={c.id} className={styles.row}>
                <span className={styles.title}>{c.title}</span>
                <span className={styles.meta}>
                  <span>{SOURCE_LABEL[c.source] ?? c.source}</span>
                  <span>{plural(c.messageCount, "message")}</span>
                  <time dateTime={c.startedAt}>{shortDate(c.startedAt)}</time>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
