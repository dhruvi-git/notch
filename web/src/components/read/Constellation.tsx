"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { dust, HEIGHT, layout, placeLabels, WIDTH, type LinkIn, type StarIn } from "./layout";
import styles from "./constellation.module.css";

const LEVEL_WORD: Record<string, string> = { CLAIMED: "claimed", SHOWN: "shown", VERIFIED: "verified", DEFENDED: "defended" };

/**
 * A candidate's skills as a constellation: brighter and bigger for stronger evidence, yellow for
 * defended, hollow for claimed, and joined where skills were used together. With `reveal`, the
 * stars arrive strongest first and the lines draw in after them (once; not with reduced motion).
 */
export function Constellation({
  stars,
  links,
  reveal = false,
  labels = 14,
  showAreas = true,
  className,
  title,
}: {
  stars: StarIn[];
  links: LinkIn[];
  reveal?: boolean;
  labels?: number;
  showAreas?: boolean;
  className?: string;
  title?: string;
}) {
  const { placed, clusters } = useMemo(() => layout(stars, links), [stars, links]);
  const background = useMemo(() => dust(70), []);
  const [active, setActive] = useState<number | null>(null);
  const [playing, setPlaying] = useState(reveal);
  // Text is sized for the drawing as shown: on a phone the drawing shrinks, so labels grow and fewer are drawn.
  const box = useRef<HTMLElement>(null);
  const [shown, setShown] = useState(WIDTH);
  useEffect(() => {
    const el = box.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([e]) => setShown(Math.max(200, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const k = Math.min(2.1, Math.max(1, 620 / shown));
  const fontSize = 12.5 * k;
  const maxLabels = shown < 520 ? Math.min(labels, 6) : labels;

  // The reveal can be switched on after the first render (once the page knows it's new), so start it here.
  useEffect(() => {
    if (!reveal) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setPlaying(false);
      return;
    }
    setPlaying(true);
    const t = window.setTimeout(() => setPlaying(false), 900 + placed.length * 70 + 1400);
    return () => window.clearTimeout(t);
  }, [reveal, placed.length]);

  const order = useMemo(() => {
    const ranked = [...placed].sort((a, b) => b.score - a.score || a.id - b.id);
    return new Map(ranked.map((p, i) => [p.id, i]));
  }, [placed]);
  const spots = useMemo(() => {
    const want = new Set(
      [...placed].sort((a, b) => b.score - a.score).slice(0, maxLabels).map((p) => p.id).concat(placed.filter((p) => p.level === "DEFENDED").map((p) => p.id)),
    );
    return placeLabels(placed, want, fontSize);
  }, [placed, labels, maxLabels, fontSize]);
  const byId = useMemo(() => new Map(placed.map((p) => [p.id, p])), [placed]);
  const near = useMemo(() => {
    if (active == null) return null;
    const s = new Set([active]);
    links.forEach((l) => {
      if (l.a === active) s.add(l.b);
      if (l.b === active) s.add(l.a);
    });
    return s;
  }, [active, links]);
  const maxWeight = Math.max(1, ...links.map((l) => l.weight));
  const summary =
    title ??
    `${placed.length} skills. Strongest: ${[...placed]
      .sort((a, b) => b.score - a.score)
      .slice(0, 3)
      .map((p) => `${p.name} ${Math.round(p.score)}`)
      .join(", ")}.`;

  return (
    <figure
      ref={box}
      className={`${styles.figure} ${playing ? styles.playing : ""} ${className ?? ""}`}
      style={{ ["--label" as string]: `${fontSize}px`, ["--area" as string]: `${10.5 * k}px` }}
    >
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={summary} className={styles.svg} onMouseLeave={() => setActive(null)}>
        <defs>
          <radialGradient id="halo">
            <stop offset="0" stopColor="#fff" stopOpacity="0.35" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </radialGradient>
          <radialGradient id="halo-gold">
            <stop offset="0" stopColor="#fedc2a" stopOpacity="0.5" />
            <stop offset="1" stopColor="#fedc2a" stopOpacity="0" />
          </radialGradient>
        </defs>
        <g aria-hidden="true">
          {background.map((d, i) => (
            <circle key={i} cx={d.x} cy={d.y} r={d.r} className={styles.dust} style={{ opacity: d.o }} />
          ))}
          {showAreas && clusters.length > 1
            ? clusters.map((c) => (
                <text key={c.domain} x={c.x} y={c.y} className={styles.area} textAnchor="middle">
                  {c.domain}
                </text>
              ))
            : null}
          {links.map((l, i) => {
            const p = byId.get(l.a), q = byId.get(l.b);
            if (!p || !q) return null;
            const on = near ? near.has(l.a) && near.has(l.b) && (l.a === active || l.b === active) : true;
            const len = Math.hypot(q.x - p.x, q.y - p.y);
            return (
              <line
                key={i}
                x1={p.x}
                y1={p.y}
                x2={q.x}
                y2={q.y}
                className={`${styles.link} ${l.weight > 1 ? styles.linkStrong : ""} ${near ? (on ? styles.linkOn : styles.dim) : ""}`}
                style={{
                  strokeWidth: 0.6 + (l.weight / maxWeight) * 1.6,
                  ["--len" as string]: len,
                  ["--delay" as string]: `${900 + placed.length * 70 + i * 18}ms`,
                }}
              />
            );
          })}
          {placed.map((p) => {
            const dim = near ? !near.has(p.id) : false;
            const gold = p.level === "DEFENDED";
            const big = p.score >= 70 || gold;
            return (
              <g
                key={p.id}
                className={`${styles.star} ${dim ? styles.dim : ""}`}
                style={{ ["--delay" as string]: `${300 + (order.get(p.id) ?? 0) * 70}ms`, transformOrigin: `${p.x}px ${p.y}px` }}
                onMouseEnter={() => setActive(p.id)}
              >
                {big ? <circle cx={p.x} cy={p.y} r={p.r * 3.2} fill={gold ? "url(#halo-gold)" : "url(#halo)"} /> : null}
                <circle
                  cx={p.x}
                  cy={p.y}
                  r={p.r}
                  className={
                    gold ? styles.defended : p.level === "VERIFIED" ? styles.verified : p.level === "SHOWN" ? styles.shown : styles.claimed
                  }
                />
                {gold ? <circle cx={p.x} cy={p.y} r={p.r + 4} className={styles.ring} /> : null}
                <circle cx={p.x} cy={p.y} r={Math.max(14, p.r + 8)} className={styles.hit} />
                {spots.has(p.id) || active === p.id ? (
                  <text
                    x={spots.get(p.id)?.x ?? p.x + p.r + 7 * k}
                    y={spots.get(p.id)?.y ?? p.y + 4}
                    textAnchor={spots.get(p.id)?.anchor ?? "start"}
                    className={`${styles.label} ${active === p.id ? styles.labelOn : ""}`}
                  >
                    {p.name}
                    {active === p.id ? <tspan className={styles.labelScore}> {Math.round(p.score)}</tspan> : null}
                  </text>
                ) : null}
              </g>
            );
          })}
        </g>
      </svg>
      <figcaption className="sr-only">
        <ul>
          {[...placed]
            .sort((a, b) => b.score - a.score)
            .map((p) => (
              <li key={p.id}>
                {p.name}: {Math.round(p.score)}, {LEVEL_WORD[p.level] ?? p.level.toLowerCase()}
              </li>
            ))}
        </ul>
      </figcaption>
    </figure>
  );
}

/** The key under a constellation: what size, brightness and colour mean. */
export function ConstellationKey({ className }: { className?: string }) {
  return (
    <ul className={`${styles.key} ${className ?? ""}`}>
      <li>
        <svg width="14" height="14" aria-hidden="true">
          <circle cx="7" cy="7" r="5" className={styles.defended} />
          <circle cx="7" cy="7" r="6.5" className={styles.ring} />
        </svg>
        Defended
      </li>
      <li>
        <svg width="14" height="14" aria-hidden="true">
          <circle cx="7" cy="7" r="5" className={styles.verified} />
        </svg>
        Verified
      </li>
      <li>
        <svg width="14" height="14" aria-hidden="true">
          <circle cx="7" cy="7" r="5" className={styles.shown} />
        </svg>
        Shown
      </li>
      <li>
        <svg width="14" height="14" aria-hidden="true">
          <circle cx="7" cy="7" r="4.5" className={styles.claimed} />
        </svg>
        Claimed
      </li>
      <li className={styles.keyLine}>
        <svg width="22" height="14" aria-hidden="true">
          <line x1="2" y1="7" x2="20" y2="7" className={styles.link} />
        </svg>
        Used together
      </li>
    </ul>
  );
}
