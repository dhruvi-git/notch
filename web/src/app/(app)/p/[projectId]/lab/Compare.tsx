import styles from "./lab.module.css";

export type CompareRow = { size: string; fast: number; slow: number };

function fmt(v: number, unit: string): string {
  const digits = v < 1 ? 2 : v < 10 ? 1 : 0;
  return `${v.toLocaleString("en", { maximumFractionDigits: digits, minimumFractionDigits: digits })} ${unit}`;
}

/**
 * Two bars per input size, Notch against the baseline, on a log scale (the differences span
 * several orders of magnitude). Every value is also written out, so the chart reads without
 * the bars.
 */
export function Compare({ rows, unit, fastLabel, slowLabel }: {
  rows: CompareRow[];
  unit: string;
  fastLabel: string;
  slowLabel: string;
}) {
  const values = rows.flatMap((r) => [r.fast, r.slow]).filter((v) => v > 0);
  const lo = Math.log10(Math.min(...values)) - 0.3;
  const hi = Math.log10(Math.max(...values));
  const width = (v: number) => `${Math.max(2, ((Math.log10(v) - lo) / (hi - lo)) * 100)}%`;
  return (
    <div className={styles.compare}>
      <div className={styles.legend} aria-hidden="true">
        <span><i className={styles.swatchFast} /> {fastLabel}</span>
        <span><i className={styles.swatchSlow} /> {slowLabel}</span>
      </div>
      <dl className={styles.rows}>
        {rows.map((r) => (
          <div key={r.size} className={styles.row}>
            <dt>{r.size}</dt>
            <dd>
              <span className={styles.barLine}>
                <span className={styles.barFast} style={{ width: width(r.fast) }} />
                <span className={styles.barValue}>
                  <span className="sr-only">{fastLabel}: </span>
                  {fmt(r.fast, unit)}
                </span>
              </span>
              <span className={styles.barLine}>
                <span className={styles.barSlow} style={{ width: width(r.slow) }} />
                <span className={styles.barValue}>
                  <span className="sr-only">{slowLabel}: </span>
                  {fmt(r.slow, unit)}
                </span>
              </span>
            </dd>
            <dd className={styles.speedup}>{Math.round(r.slow / r.fast).toLocaleString("en")}× faster</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
