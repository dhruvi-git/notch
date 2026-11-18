/**
 * A small, dependency-free syntax colourer for the code shown in Ownership Checks. It only
 * needs to make a function readable at a glance: comments, strings, numbers, keywords and
 * calls. Anything it doesn't recognise is plain text, so it can never hide characters.
 */

export type TokenKind = "plain" | "comment" | "string" | "number" | "keyword" | "call" | "type";
export type Token = { kind: TokenKind; text: string };

const KEYWORDS: Record<string, string[]> = {
  python: "def class return if elif else for while in not and or is None True False import from as with try except finally raise lambda yield pass break continue async await global nonlocal self".split(" "),
  js: "function const let var return if else for while of in new class extends import from export default async await try catch finally throw typeof instanceof null undefined true false this switch case break continue yield".split(" "),
  java: "public private protected static final class interface record enum extends implements return if else for while new try catch finally throw throws void int long double boolean char var null true false this super import package switch case break continue".split(" "),
  sql: "select from where join left right inner outer full on group by order having limit offset as and or not null is in exists case when then else end union all distinct insert into values update set delete with over partition count sum avg min max desc asc between like".split(" "),
  go: "func package import return if else for range var const type struct interface map chan go defer select switch case nil true false".split(" "),
};

const FAMILY: Record<string, keyof typeof KEYWORDS> = {
  python: "python",
  javascript: "js",
  typescript: "js",
  tsx: "js",
  jsx: "js",
  java: "java",
  kotlin: "java",
  csharp: "java",
  c: "java",
  cpp: "java",
  sql: "sql",
  go: "go",
};

const LINE_COMMENT: Record<keyof typeof KEYWORDS, string> = { python: "#", js: "//", java: "//", sql: "--", go: "//" };

export function tokenize(line: string, language: string): Token[] {
  const fam = FAMILY[language.toLowerCase()] ?? "js";
  const words = new Set(KEYWORDS[fam]);
  const comment = LINE_COMMENT[fam];
  const out: Token[] = [];
  let i = 0;
  const push = (kind: TokenKind, text: string) => {
    const last = out[out.length - 1];
    if (last && last.kind === kind) last.text += text;
    else out.push({ kind, text });
  };
  while (i < line.length) {
    const rest = line.slice(i);
    if (rest.startsWith(comment)) {
      push("comment", rest);
      break;
    }
    const q = rest[0];
    if (q === '"' || q === "'" || q === "`") {
      let j = 1;
      while (j < rest.length && rest[j] !== q) j += rest[j] === "\\" ? 2 : 1;
      push("string", rest.slice(0, j + 1));
      i += j + 1;
      continue;
    }
    const num = /^\d[\d_.]*/.exec(rest);
    if (num && !/[\w]/.test(line[i - 1] ?? "")) {
      push("number", num[0]);
      i += num[0].length;
      continue;
    }
    const word = /^[A-Za-z_$][\w$]*/.exec(rest);
    if (word) {
      const w = word[0];
      const after = rest.slice(w.length);
      const kw = fam === "sql" ? words.has(w.toLowerCase()) : words.has(w);
      push(kw ? "keyword" : /^\s*\(/.test(after) ? "call" : /^[A-Z]/.test(w) && fam !== "sql" ? "type" : "plain", w);
      i += w.length;
      continue;
    }
    push("plain", rest[0]);
    i += 1;
  }
  return out;
}
