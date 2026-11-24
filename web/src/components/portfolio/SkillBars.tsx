import { SealDot } from "@/components/Seal";
import { LEVEL_LABEL, UNDEFENDED_CAP, type Level, type SkillView } from "@/lib/portfolio-types";
import styles from "./portfolio.module.css";

export function LevelChip({ level }: { level: Level }) {
  return (
    <span className={`${styles.level} ${styles[`level${level}`]}`}>
      {level === "DEFENDED" ? <SealDot /> : null}
      {LEVEL_LABEL[level]}
    </span>
  );
}

/**
 * Skills grouped by area, strongest first: a bar for the 0–100 score, the evidence level and any
 * rank badge. Every track has a notch at 85, the most one repository can earn a skill on its own;
 * anything past it (several repositories, or a defended one) is drawn in highlighter yellow.
 */
export function SkillBars({ skills, linkToEvidence = false }: { skills: SkillView[]; linkToEvidence?: boolean }) {
  const groups = new Map<string, SkillView[]>();
  for (const s of skills) {
    const list = groups.get(s.domain) ?? [];
    list.push(s);
    groups.set(s.domain, list);
  }
  // Areas in order of their best skill, so the strongest area comes first.
  const ordered = [...groups.entries()].sort((a, b) => b[1][0].score - a[1][0].score);
  return (
    <div>
      {ordered.map(([domain, list]) => (
        <div key={domain} className={styles.group}>
          <h3 className={styles.eyebrow}>{domain}</h3>
          <ul className={styles.rows}>
            {list.map((s, i) => (
              <li key={s.id} className={styles.row}>
                <span className={styles.skillName}>
                  {linkToEvidence && s.evidenceIds.length > 0 ? <a href={`#evidence-${s.evidenceIds[0]}`}>{s.name}</a> : s.name}
                </span>
                <span
                  className={styles.track}
                  role="img"
                  aria-label={`${s.name}: ${Math.round(s.score)} out of 100`}
                >
                  <span
                    className={`${styles.fill} grow-x`}
                    style={{
                      ["--i" as string]: Math.min(i, 10),
                      width: `${Math.max(0, Math.min(100, s.score))}%`,
                      ["--cut" as string]: `${(UNDEFENDED_CAP / Math.max(s.score, 1)) * 100}%`,
                    }}
                  />
                </span>
                <span className={styles.score} aria-hidden="true">
                  {Math.round(s.score)}
                </span>
                <LevelChip level={s.level} />
                {s.topPercent ? <span className={styles.band}>Top {s.topPercent}%</span> : <span aria-hidden="true" />}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
