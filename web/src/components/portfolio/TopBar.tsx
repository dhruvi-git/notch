import Link from "next/link";
import { Brand } from "@/components/Brand";
import { Avatar } from "@/components/UserMenu";
import type { User } from "@/lib/types";
import styles from "./portfolio.module.css";

/**
 * The header of the signed-in screens. Candidates have two places, You (their read) and Profile
 * (what everyone else sees); recruiters have Hire. "dark" joins the header to a plum hero below.
 */
export function TopBar({
  user,
  current,
  tone = "light",
  profileHandle = null,
}: {
  user: User;
  current: "profile" | "hire";
  tone?: "light" | "dark";
  profileHandle?: string | null;
}) {
  return (
    <header className={`${styles.topBar} ${tone === "dark" ? `${styles.topBarDark} on-brand` : ""}`}>
      <Brand href="/" />
      <nav aria-label="Main" className={styles.topLinks}>
        <Link href="/start" aria-current={current === "profile" ? "page" : undefined}>
          You
        </Link>
        {profileHandle ? <Link href={`/u/${profileHandle}`}>Profile</Link> : null}
        <Link href="/hire" aria-current={current === "hire" ? "page" : undefined}>
          Hire
        </Link>
        <Link href="/settings">Settings</Link>
      </nav>
      <Avatar user={user} />
    </header>
  );
}
