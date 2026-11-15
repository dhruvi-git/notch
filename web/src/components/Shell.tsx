import type { Project, Usage, User } from "@/lib/types";
import type { Theme } from "@/lib/theme";
import { plural } from "@/lib/format";
import { Brand } from "./Brand";
import { NavLinks, type NavItem } from "./NavLinks";
import { ProjectSwitcher } from "./ProjectSwitcher";
import { UserMenu } from "./UserMenu";
import styles from "./shell.module.css";

type Props = {
  project: Project;
  projects: Project[];
  user: User;
  usage: Usage;
  theme: Theme;
  driftCount: number;
  children: React.ReactNode;
};

/** The frame around every project page: a rail with the project, its sections and the person. */
export function Shell({ project, projects, user, usage, theme, driftCount, children }: Props) {
  const base = `/p/${project.id}`;
  // Grouped by what the person is doing: their code, the project's memory, adding to it.
  const home: NavItem[] = [{ href: base, label: "Overview", exact: true }];
  const code: NavItem[] = [
    { href: `${base}/review`, label: "Review" },
    { href: `${base}/explain`, label: "Explain" },
  ];
  const memory: NavItem[] = [
    { href: `${base}/resume`, label: "Where you stopped" },
    { href: `${base}/recall`, label: "Ask" },
    { href: `${base}/ledger`, label: "Ledger" },
    { href: `${base}/drift`, label: "Drift", badge: driftCount },
    { href: `${base}/conversations`, label: "Conversations" },
  ];
  const add: NavItem[] = [
    { href: `${base}/imports`, label: "Upload" },
    { href: `${base}/sync`, label: "Connect repo" },
  ];
  const more: NavItem[] = [
    { href: `${base}/compare`, label: "Compare options" },
    { href: `${base}/lab`, label: "Under the hood" },
  ];
  return (
    <div className={styles.shell}>
      <aside className={`${styles.rail} on-brand`}>
        <div className={styles.railTop}>
          <Brand />
          <ProjectSwitcher current={project} projects={projects} />
          {project.isPrivate ? <p className={styles.private}>Private: never sent to an AI model</p> : null}
        </div>
        <NavLinks items={home} label="Project" />
        <NavLinks items={code} label="Your code" heading="Your code" />
        <NavLinks items={memory} label="Project memory" heading="Project memory" />
        <NavLinks items={add} label="Add to it" heading="Add to it" />
        <NavLinks items={more} label="More" heading="More" />
        <div className={styles.railBottom}>
          <p className={styles.usage}>
            {plural(usage.remaining.import, "upload")} and {plural(usage.remaining.question, "question")} left today
          </p>
          <UserMenu user={user} theme={theme} />
        </div>
      </aside>
      <main className={styles.main} id="main">
        {children}
      </main>
    </div>
  );
}
