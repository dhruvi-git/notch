import type { Metadata } from "next";
import Link from "next/link";
import { cookies } from "next/headers";
import { Brand } from "@/components/Brand";
import { UserMenu } from "@/components/UserMenu";
import { api } from "@/lib/server-api";
import { shortDate } from "@/lib/format";
import { THEME_COOKIE, parseTheme } from "@/lib/theme";
import type { Project, User } from "@/lib/types";
import { NewProjectForm } from "./NewProjectForm";
import styles from "./projects.module.css";

export const metadata: Metadata = { title: "Projects" };

export default async function Projects() {
  const [user, projects] = await Promise.all([api<User>("/api/me"), api<Project[]>("/api/projects")]);
  const theme = parseTheme((await cookies()).get(THEME_COOKIE)?.value);
  return (
    <div className={styles.page}>
      <header className={`${styles.header} on-brand`}>
        <Brand />
        <UserMenu user={user} theme={theme} inline />
      </header>
      <main className={styles.main}>
        <h1>Projects</h1>
        <p className={styles.intro}>
          A project holds the conversations, decisions and notes for one codebase or piece of work.
        </p>
        {projects.length > 0 ? (
          <ul className={styles.list}>
            {projects.map((p) => (
              <li key={p.id}>
                <Link href={`/p/${p.id}`} className={styles.row}>
                  <span className={styles.name}>{p.name}</span>
                  <span className={styles.meta}>
                    {p.isPrivate ? <span className={styles.private}>Private</span> : null}
                    <span>Created {shortDate(p.createdAt)}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
        <NewProjectForm first={projects.length === 0} />
      </main>
    </div>
  );
}
