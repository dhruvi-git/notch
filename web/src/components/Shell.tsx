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
  const nav: NavItem[] = [
    { href: `${base}/recall`, label: "Recall" },
    { href: `${base}/resume`, label: "Where you stopped" },
    { href: `${base}/ledger`, label: "Ledger" },
    { href: `${base}/drift`, label: "Drift", badge: driftCount },
    { href: `${base}/sync`, label: "Sync" },
    { href: `${base}/imports`, label: "Imports" },
    { href: `${base}/conversations`, label: "Conversations" },
  ];
  const more: NavItem[] = [{ href: `${base}/lab`, label: "Lab" }];
  return (
    <div className={styles.shell}>
      <aside className={styles.rail}>
        <div className={styles.railTop}>
          <Brand />
          <ProjectSwitcher current={project} projects={projects} />
          {project.isPrivate ? <p className={styles.private}>Private: never sent to an AI model</p> : null}
        </div>
        <NavLinks items={nav} />
        <NavLinks items={more} label="More" />
        <div className={styles.railBottom}>
          <p className={styles.usage}>
            {plural(usage.remaining.import, "import")} and {plural(usage.remaining.question, "question")} left today
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
