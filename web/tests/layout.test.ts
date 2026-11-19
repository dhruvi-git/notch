// Run with: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { HEIGHT, layout, placeLabels, WIDTH } from "../src/components/read/layout.ts";

const STARS = [
  { id: 17, name: "SQL", domain: "Data", score: 92, level: "VERIFIED" },
  { id: 20, name: "pandas", domain: "Data", score: 88, level: "VERIFIED" },
  { id: 23, name: "Power BI", domain: "Data", score: 74, level: "VERIFIED" },
  { id: 26, name: "Data Visualization", domain: "Data", score: 74, level: "VERIFIED" },
  { id: 1, name: "Python", domain: "Languages", score: 94, level: "DEFENDED" },
  { id: 60, name: "React", domain: "Web", score: 44, level: "SHOWN" },
  { id: 64, name: "Node.js", domain: "Web", score: 44, level: "SHOWN" },
  { id: 102, name: "Docker", domain: "DevOps & Cloud", score: 0, level: "CLAIMED" },
];
const LINKS = [{ a: 17, b: 20, weight: 3 }, { a: 1, b: 17, weight: 3 }, { a: 60, b: 64, weight: 1 }, { a: 1, b: 20, weight: 2 }];

test("the same skills and links always give the same picture, inside the frame", () => {
  const a = layout(STARS, LINKS), b = layout([...STARS].reverse(), LINKS);
  const pos = (r: ReturnType<typeof layout>) => r.placed.map((p) => [p.id, Math.round(p.x), Math.round(p.y)]).sort((x, y) => x[0] - y[0]);
  assert.deepEqual(pos(a), pos(b));
  for (const p of a.placed) {
    assert.ok(p.x - p.r >= 0 && p.x + p.r <= WIDTH && p.y - p.r >= 0 && p.y + p.r <= HEIGHT, `${p.name} is inside`);
  }
  assert.equal(a.clusters.length, 4);
});

test("stars keep their distance and linked skills sit closer than unlinked ones", () => {
  const { placed } = layout(STARS, LINKS);
  for (let i = 0; i < placed.length; i++) for (let j = i + 1; j < placed.length; j++) {
    const p = placed[i], q = placed[j];
    assert.ok(Math.hypot(p.x - q.x, p.y - q.y) > p.r + q.r, `${p.name} and ${q.name} don't overlap`);
  }
  const at = (id: number) => placed.find((p) => p.id === id)!;
  const d = (a: number, b: number) => Math.hypot(at(a).x - at(b).x, at(a).y - at(b).y);
  assert.ok(d(1, 17) < d(1, 64), "Python sits nearer SQL (used together 3 times) than Node.js (never)");
});

test("labels never overlap each other or a star, and stay in the frame", () => {
  const { placed } = layout(STARS, LINKS);
  const spots = placeLabels(placed, new Set(placed.map((p) => p.id)), 12.5);
  assert.ok(spots.size >= 6, `most stars get a label (${spots.size})`);
  const boxes = [...spots.entries()].map(([id, s]) => {
    const name = placed.find((p) => p.id === id)!.name;
    const w = name.length * 12.5 * 0.56 + 4;
    const x0 = s.anchor === "start" ? s.x : s.anchor === "end" ? s.x - w : s.x - w / 2;
    return { x0, x1: x0 + w, y0: s.y - 12.5, y1: s.y + 3 };
  });
  for (let i = 0; i < boxes.length; i++) {
    assert.ok(boxes[i].x0 >= 0 && boxes[i].x1 <= WIDTH, "inside the frame");
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      assert.ok(!(a.x0 < b.x1 - 1 && a.x1 > b.x0 + 1 && a.y0 < b.y1 - 1 && a.y1 > b.y0 + 1), "labels don't collide");
    }
  }
});

test("edge cases: no skills, and a single skill", () => {
  assert.deepEqual(layout([], []), { placed: [], clusters: [] });
  const one = layout([STARS[0]], []);
  assert.equal(one.placed.length, 1);
  assert.ok(Number.isFinite(one.placed[0].x) && Number.isFinite(one.placed[0].y));
});
