"use client";

import { useEffect, useId, useState } from "react";
import { call } from "@/lib/client-api";
import type { SkillRef } from "@/lib/portfolio-types";
import styles from "./skill-picker.module.css";

/**
 * Type a few letters, pick a skill from the catalogue (autocomplete from the API's radix tree).
 * Only known skills can be picked, so every skill on Notch means the same thing everywhere.
 */
export function SkillPicker({ label, onPick, exclude = [] }: { label: string; onPick: (s: SkillRef) => void; exclude?: number[] }) {
  const id = useId();
  const [q, setQ] = useState("");
  const [options, setOptions] = useState<SkillRef[]>([]);

  useEffect(() => {
    const query = q.trim();
    if (!query) {
      setOptions([]);
      return;
    }
    let live = true;
    const t = setTimeout(async () => {
      try {
        const found = await call<SkillRef[]>(`/skills/autocomplete?q=${encodeURIComponent(query)}`);
        if (live) setOptions(found);
      } catch {
        if (live) setOptions([]);
      }
    }, 150);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [q]);

  const shown = options.filter((o) => !exclude.includes(o.id));
  return (
    <div className={styles.picker}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        id={id}
        className="field"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder="Type a skill, e.g. SQL or React"
        autoComplete="off"
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            if (shown[0]) {
              onPick(shown[0]);
              setQ("");
            }
          }
        }}
      />
      {shown.length > 0 ? (
        <ul className={styles.options} aria-label="Matching skills">
          {shown.map((o) => (
            <li key={o.id}>
              <button
                type="button"
                className={styles.option}
                onClick={() => {
                  onPick(o);
                  setQ("");
                }}
              >
                {o.name}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
