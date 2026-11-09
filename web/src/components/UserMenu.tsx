import type { User } from "@/lib/types";
import type { Theme } from "@/lib/theme";
import { ThemeToggle } from "./ThemeToggle";
import styles from "./user-menu.module.css";

export function Avatar({ user, size = 28 }: { user: User; size?: number }) {
  if (user.avatarUrl) {
    // eslint-disable-next-line @next/next/no-img-element -- small remote avatar, no optimisation needed
    return <img src={user.avatarUrl} alt="" width={size} height={size} className={styles.avatar} />;
  }
  return (
    <span className={styles.initial} style={{ width: size, height: size }} aria-hidden="true">
      {user.handle.slice(0, 1).toUpperCase()}
    </span>
  );
}

export function UserMenu({ user, theme, inline = false }: { user: User; theme: Theme; inline?: boolean }) {
  return (
    <div className={inline ? `${styles.menu} ${styles.inline}` : styles.menu}>
      <div className={styles.who}>
        <Avatar user={user} />
        <span className={styles.handle}>{user.handle}</span>
      </div>
      <div className={styles.row}>
        <ThemeToggle initial={theme} />
        <form action="/auth/sign-out" method="post">
          <button type="submit" className={styles.signOut}>
            Sign out
          </button>
        </form>
      </div>
    </div>
  );
}
