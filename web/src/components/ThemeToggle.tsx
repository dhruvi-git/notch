"use client";

import { useState } from "react";
import { THEME_COOKIE, type Theme } from "@/lib/theme";
import styles from "./theme-toggle.module.css";

const OPTIONS: { value: Theme; label: string }[] = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "system", label: "Auto" },
];

/** Light, dark, or follow the system. Saved in a cookie so the server renders it without a flash. */
export function ThemeToggle({ initial }: { initial: Theme }) {
  const [theme, setTheme] = useState<Theme>(initial);

  function choose(next: Theme) {
    setTheme(next);
    const root = document.documentElement;
    if (next === "system") delete root.dataset.theme;
    else root.dataset.theme = next;
    document.cookie = `${THEME_COOKIE}=${next}; path=/; max-age=31536000; samesite=lax`;
  }

  return (
    <fieldset className={styles.toggle}>
      <legend className="sr-only">Theme</legend>
      {OPTIONS.map((o) => (
        <label key={o.value} className={styles.option} data-active={theme === o.value}>
          <input
            type="radio"
            name="theme"
            value={o.value}
            checked={theme === o.value}
            onChange={() => choose(o.value)}
            className="sr-only"
          />
          {o.label}
        </label>
      ))}
    </fieldset>
  );
}
