import Link from "next/link";
import { Brand } from "@/components/Brand";
import { Avatar } from "@/components/UserMenu";
import type { User } from "@/lib/types";
import styles from "./portfolio.module.css";

/** The header of the signed-in portfolio and hiring screens. */
export function TopBar({ user, current }: { user: User; current: "profile" | "hire" }) {
  return (
    <header className={styles.topBar}>
      <Brand href="/" />
      <nav aria-label="Main" className={styles.topLinks}>
        <Link href="/start" aria-current={current === "profile" ? "page" : undefined}>
          My profile
        </Link>
        <Link href="/hire" aria-current={current === "hire" ? "page" : undefined}>
          Hire
        </Link>
        <Link href="/settings">Settings</Link>
      </nav>
      <Avatar user={user} />
    </header>
  );
}
