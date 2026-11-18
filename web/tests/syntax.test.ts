// Run with: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { tokenize } from "../src/lib/syntax.ts";

const join = (line: string, lang: string) => tokenize(line, lang).map((t) => t.text).join("");
const kinds = (line: string, lang: string) => tokenize(line, lang).filter((t) => t.kind !== "plain").map((t) => `${t.kind}:${t.text}`);

test("never drops or changes a character of the code", () => {
  const lines = [
    `merged = stores.merge(orders, on="store_id", how="left")  # keep every store`,
    `SELECT *, DENSE_RANK() OVER (PARTITION BY category ORDER BY revenue DESC) AS place -- top`,
    `const total = items.reduce((n, x) => n + x.price * 2, 0); // sum`,
    `s = 'it\\'s' + "unterminated`,
    `x-->0 && y<=1 ?? "é\u{1F600}"`,
    ``,
  ];
  for (const lang of ["python", "sql", "typescript", "java", "go", "unknown"]) for (const l of lines) assert.equal(join(l, lang), l);
});

test("colours keywords, calls, strings, numbers and comments", () => {
  assert.deepEqual(kinds(`def f(x): return g(x, 2)  # done`, "python"), [
    "keyword:def", "call:f", "keyword:return", "call:g", "number:2", "comment:# done",
  ]);
  assert.deepEqual(kinds(`select count(*) from t where a = 'x'`, "sql"), [
    "keyword:select", "keyword:count", "keyword:from", "keyword:where", "string:'x'",
  ]);
});

test("a digit inside a name is not a number, and comment markers inside strings are not comments", () => {
  assert.deepEqual(kinds(`col2 = "a # b"`, "python"), [`string:"a # b"`]);
});
