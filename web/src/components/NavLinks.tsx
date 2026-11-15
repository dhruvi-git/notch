"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "./shell.module.css";

/** {@code exact}: active only on this very page, not on pages under it (the project's Overview). */
export type NavItem = { href: string; label: string; badge?: number; exact?: boolean };

/** Section links for a project; marks the current one for screen readers too. */
export function NavLinks({ items, label = "Project sections", heading }: { items: NavItem[]; label?: string; heading?: string }) {
  const pathname = usePathname();
  return (
    <nav aria-label={label}>
      {heading ? <p className={styles.navHeading} aria-hidden="true">{heading}</p> : null}
      <ul className={styles.nav}>
        {items.map((item) => {
          const active = pathname === item.href || (!item.exact && pathname.startsWith(item.href + "/"));
          return (
            <li key={item.href}>
              <Link href={item.href} className={styles.navLink} aria-current={active ? "page" : undefined}>
                <span>{item.label}</span>
                {item.badge ? (
                  <span className={styles.badge} aria-label={`${item.badge} to review`}>
                    {item.badge}
                  </span>
                ) : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
