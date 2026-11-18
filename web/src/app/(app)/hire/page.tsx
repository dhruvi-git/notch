import type { Metadata } from "next";
import Link from "next/link";
import { TopBar } from "@/components/portfolio/TopBar";
import { api } from "@/lib/server-api";
import { shortDate } from "@/lib/format";
import type { DriveSummary } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import { NewDriveForm } from "./NewDriveForm";
import styles from "./hire.module.css";

export const metadata: Metadata = { title: "Hire" };

export default async function Hire() {
  const [user, drives] = await Promise.all([api<User>("/api/me"), api<DriveSummary[]>("/api/drives")]);
  return (
    <div>
      <TopBar user={user} current="hire" />
      <main className={`${common.container} ${common.narrow}`}>
        <h1 className={common.title}>Hiring drives</h1>
        <p className={common.lede}>Paste a job description. Notch reads the skills it asks for and ranks candidates on verified work.</p>
        {drives.length > 0 ? (
          <ul className={styles.driveList}>
            {drives.map((d) => (
              <li key={d.id}>
                <Link href={`/hire/${d.id}`} className={styles.driveRow}>
                  <span className={styles.driveTitle}>{d.title}</span>
                  <span className={`${common.muted} ${common.small}`}>Created {shortDate(d.createdAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
        <NewDriveForm first={drives.length === 0} />
      </main>
    </div>
  );
}
