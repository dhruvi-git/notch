"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import { plural } from "@/lib/format";
import type { AskResponse, SearchResponse, Suggestion } from "@/lib/types";
import { Answer } from "./Answer";
import { Results } from "./Results";
import styles from "./recall.module.css";

const DEBOUNCE_MS = 220;
const EXAMPLES = ["What did we decide about the database?", "ECONNREFUSED", "How do we rate limit?"];

type Props = { projectId: string; initialQuery: string; questionsLeft: number; hasContent: boolean };

/** The @token being typed at the caret, if any. */
function mentionAt(value: string, caret: number): { start: number; prefix: string } | null {
  const m = value.slice(0, caret).match(/(^|\s)@([^\s@]*)$/);
  return m ? { start: caret - m[2].length - 1, prefix: m[2] } : null;
}

export function Recall({ projectId, initialQuery, questionsLeft: initialLeft, hasContent }: Props) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const listId = useId();
  const [query, setQuery] = useState(initialQuery);
  const [results, setResults] = useState<SearchResponse | null>(null);
  const [searching, setSearching] = useState(false);
  const [answer, setAnswer] = useState<AskResponse | null>(null);
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [questionsLeft, setQuestionsLeft] = useState(initialLeft);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [active, setActive] = useState(0);
  const [mention, setMention] = useState<{ start: number; prefix: string } | null>(null);
  const searchAbort = useRef<AbortController | null>(null);

  const runSearch = useCallback(
    async (q: string) => {
      searchAbort.current?.abort();
      if (q.trim().length < 2) {
        setResults(null);
        setSearching(false);
        return;
      }
      const ctrl = new AbortController();
      searchAbort.current = ctrl;
      setSearching(true);
      try {
        const r = await call<SearchResponse>(`/projects/${projectId}/search?q=${encodeURIComponent(q)}&k=12`, {
          signal: ctrl.signal,
        });
        setResults(r);
        setError(null);
      } catch (err) {
        if ((err as Error).name === "AbortError") return;
        setError(err instanceof ClientApiError ? err.message : "Search didn't finish. Try again.");
      } finally {
        if (searchAbort.current === ctrl) setSearching(false);
      }
    },
    [projectId],
  );

  // Search as you type; keep the query in the address bar so a search can be shared or revisited.
  useEffect(() => {
    const t = setTimeout(() => {
      void runSearch(query);
      const url = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : "?";
      window.history.replaceState(null, "", url);
    }, DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [query, runSearch]);

  // Autocomplete for @mentions.
  useEffect(() => {
    if (!mention) {
      setSuggestions([]);
      return;
    }
    const ctrl = new AbortController();
    const t = setTimeout(async () => {
      try {
        const s = await call<Suggestion[]>(
          `/projects/${projectId}/autocomplete?prefix=${encodeURIComponent(mention.prefix)}&k=8`,
          { signal: ctrl.signal },
        );
        setSuggestions(s);
        setActive(0);
      } catch {
        setSuggestions([]);
      }
    }, 80);
    return () => {
      clearTimeout(t);
      ctrl.abort();
    };
  }, [mention, projectId]);

  function onChange(e: React.ChangeEvent<HTMLInputElement>) {
    setQuery(e.target.value);
    setAnswer(null);
    setMention(mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length));
  }

  function accept(s: Suggestion) {
    if (!mention) return;
    const caret = mention.start + 1 + mention.prefix.length;
    const next = query.slice(0, mention.start) + s.key + " " + query.slice(caret);
    setQuery(next);
    setMention(null);
    requestAnimationFrame(() => {
      const pos = mention.start + s.key.length + 1;
      input.current?.setSelectionRange(pos, pos);
      input.current?.focus();
    });
  }

  async function ask() {
    const q = query.trim();
    if (q.length < 2 || asking) return;
    setAsking(true);
    setError(null);
    try {
      const a = await postJson<AskResponse>(`/projects/${projectId}/ask`, { question: q });
      setAnswer(a);
      setQuestionsLeft(a.remainingQuestions);
      router.refresh();  // updates the allowance in the rail
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The answer didn't come back. Try again.");
    } finally {
      setAsking(false);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    const open = suggestions.length > 0 && mention;
    if (open && e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => (a + 1) % suggestions.length);
    } else if (open && e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => (a - 1 + suggestions.length) % suggestions.length);
    } else if (open && (e.key === "Enter" || e.key === "Tab")) {
      e.preventDefault();
      accept(suggestions[active]);
    } else if (open && e.key === "Escape") {
      setMention(null);
    } else if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      void ask();
    } else if (e.key === "Enter") {
      e.preventDefault();
      void runSearch(query);
    }
  }

  const listOpen = suggestions.length > 0 && mention !== null;
  const outOfQuestions = questionsLeft <= 0;

  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="recall-title">
        <h1 id="recall-title" className={styles.stageTitle}>
          Ask this project anything
        </h1>
      <div className={styles.searchBlock}>
        <div className={styles.searchRow}>
          <div className={styles.inputWrap}>
            <svg className={styles.icon} width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">
              <circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" strokeWidth="1.6" />
              <path d="m13 13 4.25 4.25" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
            <input
              ref={input}
              type="search"
              className={styles.input}
              value={query}
              onChange={onChange}
              onKeyDown={onKeyDown}
              onBlur={() => setTimeout(() => setMention(null), 120)}
              placeholder="Ask or search this project"
              aria-label="Ask or search this project"
              role="combobox"
              aria-expanded={listOpen}
              aria-controls={listId}
              aria-autocomplete="list"
              aria-activedescendant={listOpen ? `${listId}-${active}` : undefined}
              autoComplete="off"
              spellCheck={false}
              autoFocus
            />
            {listOpen ? (
              <ul id={listId} role="listbox" className={styles.suggestions} aria-label="Suggestions">
                {suggestions.map((s, i) => (
                  <li
                    key={s.key}
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={i === active}
                    className={styles.suggestion}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      accept(s);
                    }}
                    onMouseEnter={() => setActive(i)}
                  >
                    <span className={styles.suggestionKey}>{s.key}</span>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
          <button
            type="button"
            className="btn btn-yellow"
            onClick={ask}
            disabled={asking || query.trim().length < 2 || outOfQuestions}
          >
            {asking ? "Writing answer…" : "Ask"}
          </button>
        </div>
        <p className={styles.hint}>
          Results update as you type. <strong>Ask</strong> writes an answer with sources
          {outOfQuestions ? "; today's questions are used up, search still works." : ` (${plural(questionsLeft, "question")} left today).`}{" "}
          Type <kbd>@</kbd> for files, errors and chats.
        </p>
      </div>
      </section>

      <div aria-live="polite" className={styles.live}>
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
        {answer ? <Answer answer={answer} /> : null}
      </div>

      {!hasContent ? (
        <section className={styles.empty}>
          <h2>Nothing to search yet</h2>
          <p className="muted">
            Recall searches the conversations in this project. <Link href={`/p/${projectId}/imports`}>Import a chat export</Link>{" "}
            and come back.
          </p>
        </section>
      ) : results ? (
        <Results results={results} searching={searching} />
      ) : (
        <section className={styles.empty}>
          <h2>Try asking</h2>
          <ul className={styles.examples}>
            {EXAMPLES.map((ex) => (
              <li key={ex}>
                <button
                  type="button"
                  className={styles.example}
                  onClick={() => {
                    setQuery(ex);
                    input.current?.focus();
                  }}
                >
                  {ex}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

