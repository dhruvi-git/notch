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
  children: React.ReactNode;
};

/** The frame around every project page: a rail with the project, its sections and the person. */
export function Shell({ project, projects, user, usage, theme, children }: Props) {
  const base = `/p/${project.id}`;
  const nav: NavItem[] = [
    { href: `${base}/imports`, label: "Imports" },
    { href: `${base}/conversations`, label: "Conversations" },
  ];
  return (
    <div className={styles.shell}>
      <aside className={styles.rail}>
        <div className={styles.railTop}>
          <Brand />
          <ProjectSwitcher current={project} projects={projects} />
          {project.isPrivate ? <p className={styles.private}>Private: never sent to an AI model</p> : null}
        </div>
        <NavLinks items={nav} />
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
