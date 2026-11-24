// Shapes returned by the portfolio and hiring-drive endpoints (api/README.md, "Portfolios and hiring drives").

export type Level = "CLAIMED" | "SHOWN" | "VERIFIED" | "DEFENDED";

export type SkillRef = { id: number; name: string };

export type SkillView = {
  id: number;
  name: string;
  domain: string;
  score: number;
  level: Level;
  /** Tightest badge band reached (1, 5, 10 or 25), or null. */
  topPercent: number | null;
  evidenceIds: string[];
};

export type EvidenceView = {
  id: string;
  kind: "GITHUB_REPO" | "PROJECT" | "CHAT_EXPORT";
  title: string;
  url: string | null;
  description: string | null;
  ownCommits: number;
  activeWeeks: number;
  fork: boolean;
  duplicate: boolean;
  lastActive: string;
  skills: SkillRef[];
  /** Suggested from a chat export and not yet approved (owner's view only). */
  pendingSkills: SkillRef[];
  /** The best finished Ownership Check on this repository, if any. */
  ownership?: OwnershipView | null;
};

export type OwnershipView = { checkId: string; score: number; defended: boolean; finishedAt: string; attempts: number };

/** One question of an Ownership Check. Score, feedback and flags appear once the check is finished. */
export type CheckQuestion = {
  position: number;
  path: string;
  startLine: number;
  endLine: number;
  language: string;
  code: string;
  concept: string | null;
  prompt: string;
  covers: string[];
  answer: string | null;
  millis: number | null;
  score: number | null;
  feedback: string | null;
  flags: string[];
};

export type CheckView = {
  id: string;
  evidenceId: string;
  /** Set on the public transcript. */
  handle: string | null;
  repo: string;
  repoUrl: string;
  status: "OPEN" | "FINISHED";
  startedAt: string;
  deadline: string;
  finishedAt: string | null;
  secondsLeft: number;
  score: number | null;
  defended: boolean;
  summary: string | null;
  questionsBy: "model" | "templates";
  gradedBy: "model" | "rubric" | "mixed" | null;
  planElements: number;
  planCovered: number;
  questions: CheckQuestion[];
};

export type ProfileView = {
  hasProfile: boolean;
  handle: string | null;
  headline: string | null;
  domain: string | null;
  isPublic: boolean;
  skills: SkillView[];
  evidence: EvidenceView[];
  poolSize: number;
};

export type GitHubSyncResult = {
  username: string;
  listed: number;
  read: number;
  added: number;
  updated: number;
  removed: number;
  forksSkipped: number;
  withoutOwnCommits: number;
  duplicates: string[];
  partial: boolean;
  note: string | null;
};

export type ChatImportResult = {
  format: string;
  conversations: number;
  suggestions: { evidenceId: string; skillId: number; skill: string; conversations: number; messages: number; depth: number }[];
};

export type Requirement = { skillId: number; skill: string; weight: number; mustHave: boolean; minLevel: Level };

export type Role = { id: string; name: string; seats: number; threshold: number; requirements: Requirement[] };

export type Drive = { id: string; title: string; description: string; createdAt: string; roles: Role[] };

export type DriveSummary = { id: string; title: string; createdAt: string };

export type Term = {
  skill: string;
  weight: number;
  skillScore: number;
  level: Level | null;
  levelFactor: number;
  contribution: number;
};

/** The smallest single change that lifts a candidate to a role's minimum match (engine MatchScoring.closestFix). */
export type Fix = { skill: string; from: number; to: number; level: Level; levelUp: boolean };

export type ShortlistCandidate = {
  userId: string;
  handle: string;
  headline: string | null;
  score: number;
  breakdown: Term[];
  /** Set when the candidate is below the role's minimum match and one change would lift them over it. */
  fix?: Fix | null;
};

/** A candidate missing one must-have whom one improvement would bring in. */
export type NearMiss = { userId: string; handle: string; headline: string | null; fix: Fix };

export type Shortlist = {
  roleId: string;
  role: string;
  poolSize: number;
  eligible: number;
  threshold?: number;
  candidates: ShortlistCandidate[];
  nearMisses?: NearMiss[];
};

/** `moved`: best-score-first greedy would have left this person out or put them in another role. */
export type Placement = { userId: string; handle: string; score: number; moved?: boolean };

export type RoleResult = { roleId: string; name: string; seats: number; filled: number; eligible: number | null; placements: Placement[] };

export type Assignment = {
  runAt: string;
  totalSeats: number;
  filled: number;
  greedyFilled: number;
  augmentingPaths: number;
  millis: number;
  roles: RoleResult[];
  /** Totals for the three methods; null on runs stored before best fit. */
  greedyScore?: number | null;
  maxFlowFilled?: number | null;
  maxFlowScore?: number | null;
  bestFitScore?: number | null;
  bestFitMillis?: number | null;
};

export const LEVEL_LABEL: Record<Level, string> = { CLAIMED: "Claimed", SHOWN: "Shown", VERIFIED: "Verified", DEFENDED: "Defended" };

/** Without a defended repository, a skill tops out here (engine SkillScoring: one repository caps at 0.85). */
export const UNDEFENDED_CAP = 85;
