/** A candidate's read, as GET /api/insights/me and GET /public/profiles/{handle}/read return it. */

export type Trait = { key: string; name: string; line: string };

export type Archetype = {
  key: string;
  name: string;
  line: string;
  /** Why this archetype, with the numbers ("62% of your proven skill is in data"). */
  reason: string;
  skills: string[];
  trait: Trait | null;
};

export type Star = { id: number; name: string; domain: string; score: number; level: string; topPercent: number | null };
export type Link = { a: number; b: number; weight: number };

export type Pair = { skillId: number; name: string; said: string; done: string };

/** What AI history says against what public work shows. Owner's view only. */
export type SaidDone = { edges: Pair[]; unproven: Pair[]; strengths: Pair[]; inSync: Pair[]; hasHistory: boolean };

export type Insight = { key: string; title: string; detail: string };

export type Stats = { repositories: number; commits: number; activeWeeks: number; skills: number; defended: number; since: string | null };

export type Rhythm = { weekdays: number[]; hours: number[]; commits: number };

export type Read = {
  archetype: Archetype;
  constellation: { stars: Star[]; links: Link[] };
  saidDone: SaidDone | null;
  insights: Insight[];
  stats: Stats;
  rhythm: Rhythm | null;
};

export type MyRead = { read: Read; goal: string | null };

export type MemoryImportResult = {
  skills: { evidenceId: string; skillId: number; name: string; mode: "USED" | "LEARNING" | "ASKED"; note: string | null }[];
  projects: { evidenceId: string; title: string; skills: string[] }[];
  skippedProjects: number;
  unrecognised: string[];
  goal: string | null;
};
