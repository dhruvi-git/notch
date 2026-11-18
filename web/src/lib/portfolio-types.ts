// Shapes returned by the portfolio and hiring-drive endpoints (api/README.md, "Portfolios and hiring drives").

export type Level = "CLAIMED" | "SHOWN" | "VERIFIED";

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

export type ShortlistCandidate = { userId: string; handle: string; headline: string | null; score: number; breakdown: Term[] };

export type Shortlist = { roleId: string; role: string; poolSize: number; eligible: number; candidates: ShortlistCandidate[] };

export type Placement = { userId: string; handle: string; score: number };

export type RoleResult = { roleId: string; name: string; seats: number; filled: number; eligible: number | null; placements: Placement[] };

export type Assignment = {
  runAt: string;
  totalSeats: number;
  filled: number;
  greedyFilled: number;
  augmentingPaths: number;
  millis: number;
  roles: RoleResult[];
};

export const LEVEL_LABEL: Record<Level, string> = { CLAIMED: "Claimed", SHOWN: "Shown", VERIFIED: "Verified" };
