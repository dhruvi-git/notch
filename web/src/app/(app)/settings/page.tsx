import type { Metadata } from "next";
import { cookies } from "next/headers";
import Link from "next/link";
import { Brand } from "@/components/Brand";
import { Avatar, UserMenu } from "@/components/UserMenu";
import { api } from "@/lib/server-api";
import { THEME_COOKIE, parseTheme } from "@/lib/theme";
import type { TokenInfo, User } from "@/lib/types";
import { Tokens } from "./Tokens";
import styles from "./settings.module.css";

export const metadata: Metadata = { title: "Settings" };

export default async function Settings() {
  const [user, tokens] = await Promise.all([api<User>("/api/me"), api<TokenInfo[]>("/api/tokens")]);
  const theme = parseTheme((await cookies()).get(THEME_COOKIE)?.value);
  return (
    <div className={styles.page}>
      <header className={`${styles.header} on-brand`}>
        <Brand />
        <UserMenu user={user} theme={theme} inline />
      </header>
      <main className={styles.main}>
        <p className={styles.back}>
          <Link href="/projects">Projects</Link>
        </p>
        <h1>Settings</h1>

        <section className={styles.section} aria-labelledby="account">
          <h2 id="account">Account</h2>
          <div className={styles.account}>
            <Avatar user={user} size={40} />
            <div>
              <p className={styles.handle}>{user.handle}</p>
              <p className="muted">{user.github ? "Signed in with GitHub" : "Local test account"}</p>
            </div>
          </div>
        </section>

        <section className={styles.section} aria-labelledby="tokens">
          <h2 id="tokens">Personal tokens</h2>
          <p className={styles.sectionIntro}>
            Tokens let the Notch browser and editor extensions save chats and look things up as you. Give each device its
            own token, so you can revoke one without affecting the others.
          </p>
          <Tokens initial={tokens} />
        </section>
      </main>
    </div>
  );
}
