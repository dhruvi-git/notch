// Run with: npm test   (Node's built-in test runner, TypeScript via type stripping)
import { test } from "node:test";
import assert from "node:assert/strict";
import { segments, snippet, toWords } from "../src/lib/highlight.ts";
import { citedNumbers, parseAnswer, parts } from "../src/lib/answer.ts";

test("segments marks spans and keeps the rest", () => {
  assert.deepEqual(segments("use postgres here", [[4, 12]]), [
    { text: "use ", marked: false },
    { text: "postgres", marked: true },
    { text: " here", marked: false },
  ]);
});

test("segments merges overlapping and unsorted spans", () => {
  const s = segments("abcdefgh", [[4, 6], [1, 3], [2, 5]]);
  assert.deepEqual(s.map((x) => [x.text, x.marked]), [["a", false], ["bcdef", true], ["gh", false]]);
});

test("segments clips spans outside the text and ignores empty ones", () => {
  assert.deepEqual(segments("abc", [[-5, 1], [2, 99], [1, 1]]).map((x) => x.text), ["a", "b", "c"]);
  assert.deepEqual(segments("", [[0, 3]]), []);
  assert.deepEqual(segments("plain", []), [{ text: "plain", marked: false }]);
});

test("snippet returns short text whole", () => {
  const s = snippet("short text", [[0, 5]], 100);
  assert.equal(s.text, "short text");
  assert.equal(s.cutStart, false);
  assert.equal(s.cutEnd, false);
});

test("snippet centres on the first match and shifts spans", () => {
  const text = "word ".repeat(100) + "ECONNREFUSED " + "tail ".repeat(100);
  const at = text.indexOf("ECONNREFUSED");
  const s = snippet(text, [[at, at + 12]], 120);
  assert.ok(s.cutStart && s.cutEnd);
  assert.ok(s.text.length <= 120);
  const [a, b] = s.spans[0];
  assert.equal(s.text.slice(a, b), "ECONNREFUSED");
});

test("parts splits citations and trims the space before them", () => {
  assert.deepEqual(parts("We chose Postgres [1] and Redis [2]."), [
    { kind: "text", text: "We chose Postgres" },
    { kind: "cite", n: 1 },
    { kind: "text", text: " and Redis" },
    { kind: "cite", n: 2 },
    { kind: "text", text: "." },
  ]);
});

test("parseAnswer reads intro and bullets", () => {
  const a = parseAnswer("Here's what this project decided:\n- Use Postgres. [1]\n- Use Redis. [2]");
  assert.deepEqual(a.intro, [{ kind: "text", text: "Here's what this project decided:" }]);
  assert.equal(a.items.length, 2);
  assert.deepEqual(citedNumbers(a), [1, 2]);
});

test("parseAnswer treats text without bullets as paragraphs", () => {
  const a = parseAnswer("I couldn't find anything in this project that answers that.");
  assert.equal(a.intro, null);
  assert.equal(a.items.length, 0);
  assert.equal(a.paragraphs.length, 1);
  assert.deepEqual(citedNumbers(a), []);
});

test("toWords widens stem matches to whole words", () => {
  const text = "Postgres and MongoDB, not mongo_db.";
  assert.deepEqual(toWords(text, [[0, 7], [14, 19], [26, 31]]), [[0, 8], [13, 20], [26, 34]]);
  assert.deepEqual(toWords("", [[0, 0]]), [[0, 0]]);
});
