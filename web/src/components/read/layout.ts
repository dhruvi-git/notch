/**
 * Lays out a constellation: one star per skill, clustered by area, with skills used together in
 * the same repository or project pulled closer. Deterministic: the same skills and links always
 * give the same picture, so a candidate's constellation is recognisably theirs.
 */

export type StarIn = { id: number; name: string; domain: string; score: number; level: string };
export type LinkIn = { a: number; b: number; weight: number };
export type Placed = StarIn & { x: number; y: number; r: number };
export type Cluster = { domain: string; x: number; y: number };
export type LabelSpot = { x: number; y: number; anchor: "start" | "end" | "middle" };

export const WIDTH = 800;
export const HEIGHT = 520;
const MARGIN = 56;
const GOLDEN = Math.PI * (3 - Math.sqrt(5));

/** Areas in a fixed order around the sky, so related areas sit next to each other. */
const AREA_ORDER = ["Data", "Databases", "AI & ML", "Languages", "CS Fundamentals", "Web", "Design", "Mobile", "DevOps & Cloud", "Other"];

export function radius(score: number, level: string) {
  return (level === "CLAIMED" ? 3 : 3.2) + (Math.max(0, Math.min(100, score)) / 100) * 7.5;
}

export function layout(stars: StarIn[], links: LinkIn[]): { placed: Placed[]; clusters: Cluster[] } {
  if (stars.length === 0) return { placed: [], clusters: [] };
  const areas = [...new Set(stars.map((s) => s.domain ?? "Other"))].sort(
    (a, b) => (AREA_ORDER.indexOf(a) + 99) % 99 - (AREA_ORDER.indexOf(b) + 99) % 99 || a.localeCompare(b),
  );
  const cx = WIDTH / 2, cy = HEIGHT / 2;
  const rx = areas.length === 1 ? 0 : Math.min(250, 120 + areas.length * 28);
  const ry = areas.length === 1 ? 0 : Math.min(165, 80 + areas.length * 20);
  const clusters: Cluster[] = areas.map((domain, i) => {
    const t = -Math.PI * 0.85 + (i / areas.length) * Math.PI * 2;
    return { domain, x: cx + rx * Math.cos(t), y: cy + ry * Math.sin(t) };
  });
  const centre = new Map(clusters.map((c) => [c.domain, c]));

  // Seed: within each area, strongest skills nearest its centre, on a sunflower spiral.
  const placed: Placed[] = [];
  for (const area of areas) {
    const members = stars.filter((s) => (s.domain ?? "Other") === area).sort((a, b) => b.score - a.score || a.id - b.id);
    const c = centre.get(area)!;
    members.forEach((s, k) => {
      const d = 22 * Math.sqrt(k + 0.6);
      const t = k * GOLDEN + (s.id % 7) * 0.31;
      placed.push({ ...s, x: c.x + d * Math.cos(t), y: c.y + d * Math.sin(t), r: radius(s.score, s.level) });
    });
  }

  // Relax: stars keep their distance, linked skills attract, each stays near its area.
  const index = new Map(placed.map((p, i) => [p.id, i]));
  const springs = links.filter((l) => index.has(l.a) && index.has(l.b));
  for (let it = 0; it < 220; it++) {
    const cool = 1 - it / 260;
    for (let i = 0; i < placed.length; i++) {
      for (let j = i + 1; j < placed.length; j++) {
        const p = placed[i], q = placed[j];
        let dx = q.x - p.x, dy = q.y - p.y;
        let d = Math.hypot(dx, dy);
        if (d < 0.01) { dx = 0.5; dy = 0.3; d = 0.58; }
        const min = p.r + q.r + 34;
        if (d < min) {
          const push = ((min - d) / d) * 0.5;
          p.x -= dx * push; p.y -= dy * push;
          q.x += dx * push; q.y += dy * push;
        }
      }
    }
    for (const l of springs) {
      const p = placed[index.get(l.a)!], q = placed[index.get(l.b)!];
      const dx = q.x - p.x, dy = q.y - p.y;
      const d = Math.hypot(dx, dy) || 1;
      const pull = ((d - 80) / d) * 0.008 * Math.min(3, l.weight) * cool;
      p.x += dx * pull; p.y += dy * pull;
      q.x -= dx * pull; q.y -= dy * pull;
    }
    for (const p of placed) {
      const c = centre.get(p.domain ?? "Other")!;
      p.x += (c.x - p.x) * 0.03;
      p.y += (c.y - p.y) * 0.03;
      p.x = Math.max(MARGIN, Math.min(WIDTH - MARGIN, p.x));
      p.y = Math.max(MARGIN * 0.7, Math.min(HEIGHT - MARGIN * 0.7, p.y));
    }
  }
  fit(placed);
  // Each area's name sits just above its own stars, wherever the relaxation moved them.
  const named: Cluster[] = areas.map((domain) => {
    const members = placed.filter((p) => (p.domain ?? "Other") === domain);
    const x = members.reduce((n, p) => n + p.x, 0) / members.length;
    const top = Math.min(...members.map((p) => p.y - p.r));
    return { domain, x, y: Math.max(14, top - 16) };
  });
  return { placed, clusters: named };
}

/** Scales and centres the finished shape to fill the frame, without stretching it more than 1.7 times. */
function fit(placed: Placed[]) {
  const xs = placed.map((p) => p.x), ys = placed.map((p) => p.y);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  const w = Math.max(1, maxX - minX), h = Math.max(1, maxY - minY);
  const padX = 120, padY = 64;
  const k = Math.min(1.7, (WIDTH - 2 * padX) / w, (HEIGHT - 2 * padY) / h);
  const ox = (WIDTH - w * k) / 2 - minX * k, oy = (HEIGHT - h * k) / 2 - minY * k;
  for (const p of placed) {
    p.x = p.x * k + ox;
    p.y = p.y * k + oy;
  }
}

/**
 * Where each label goes: right of its star, else left, above or below, whichever overlaps no
 * other label or star. Strongest skills are placed first; a label with no free spot is left off
 * (it still shows on hover).
 */
export function placeLabels(placed: Placed[], want: Set<number>, fontSize = 12.5): Map<number, LabelSpot> {
  type Box = { x0: number; y0: number; x1: number; y1: number };
  const boxes: Box[] = placed.map((p) => ({ x0: p.x - p.r - 2, y0: p.y - p.r - 2, x1: p.x + p.r + 2, y1: p.y + p.r + 2 }));
  const hit = (b: Box) => boxes.some((o) => b.x0 < o.x1 && b.x1 > o.x0 && b.y0 < o.y1 && b.y1 > o.y0);
  const out = new Map<number, LabelSpot>();
  const ranked = placed.filter((p) => want.has(p.id)).sort((a, b) => b.score - a.score || a.id - b.id);
  for (const p of ranked) {
    const w = p.name.length * fontSize * 0.56 + 4, h = fontSize + 4, gap = p.r + fontSize * 0.56;
    const tries: [LabelSpot, Box][] = [
      [{ x: p.x + gap, y: p.y + fontSize * 0.35, anchor: "start" }, { x0: p.x + gap, y0: p.y - h / 2, x1: p.x + gap + w, y1: p.y + h / 2 }],
      [{ x: p.x - gap, y: p.y + fontSize * 0.35, anchor: "end" }, { x0: p.x - gap - w, y0: p.y - h / 2, x1: p.x - gap, y1: p.y + h / 2 }],
      [{ x: p.x, y: p.y - gap - 2, anchor: "middle" }, { x0: p.x - w / 2, y0: p.y - gap - h, x1: p.x + w / 2, y1: p.y - gap }],
      [{ x: p.x, y: p.y + gap + fontSize, anchor: "middle" }, { x0: p.x - w / 2, y0: p.y + gap, x1: p.x + w / 2, y1: p.y + gap + h }],
    ];
    for (const [spot, box] of tries) {
      if (box.x0 < 2 || box.x1 > WIDTH - 2 || box.y0 < 2 || box.y1 > HEIGHT - 2 || hit(box)) continue;
      boxes.push(box);
      out.set(p.id, spot);
      break;
    }
  }
  return out;
}

/** Faint background stars, the same every time. */
export function dust(count: number, seed = 7) {
  let s = seed;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  return Array.from({ length: count }, () => ({ x: rnd() * WIDTH, y: rnd() * HEIGHT, r: 0.4 + rnd() * 0.8, o: 0.08 + rnd() * 0.22 }));
}
