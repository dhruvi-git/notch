/** Shapes returned by the Notch API. Kept in step with the Java records of the same names. */

export type User = {
  id: string;
  handle: string;
  avatarUrl: string | null;
  github: boolean;
};

export type Project = {
  id: string;
  name: string;
  isPrivate: boolean;
  createdAt: string;
};

export type ImportStatus = "PROCESSING" | "READY" | "FAILED";

export type ImportReport = {
  id: string;
  projectId: string;
  filename: string | null;
  format: "chatgpt" | "claude" | "markdown" | "text";
  status: ImportStatus;
  conversationsAdded: number;
  conversationsUpdated: number;
  messagesAdded: number;
  messagesSkipped: number;
  chunksAdded: number;
  duplicateChunks: number;
  bloomFalsePositives: number;
  secretsRedacted: number;
  memoriesExtracted: number;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
};

export type ConversationSummary = {
  id: string;
  source: ImportReport["format"];
  title: string;
  startedAt: string;
  messageCount: number;
};

export type Usage = {
  remaining: { import: number; question: number };
  limits: { import: number; question: number };
};

export type AuthConfig = { github: boolean; google: boolean; devLogin: boolean };

export type Session = { token: string; expiresAt: string; user: User };

/** RFC 9457 problem response with the API's stable error code. */
export type Problem = { status: number; title?: string; detail?: string; code?: string };

/** Where a search hit or citation came from. */
export type SourceRef = {
  chunkId: string;
  messageId: string;
  conversationId: string;
  conversationTitle: string;
  role: string;
  sentAt: string;
};

export type Why = { exact: number; semantic: number; recency: number; decision: number };

export type SearchHit = {
  source: SourceRef;
  text: string;
  highlights: [number, number][];
  score: number;
  why: Why;
  decision: boolean;
  threadId: string;
};

export type SearchResponse = {
  query: string;
  terms: string[];
  phrases: string[];
  expansions: string[];
  decisionIntent: boolean;
  since: string | null;
  hits: SearchHit[];
  exactCandidates: number;
  semanticCandidates: number;
  indexVersion: number;
  tookMillis: number;
};

export type Citation = { n: number; source: SourceRef; text: string; start: number; end: number; quote: string };

export type AskResponse = {
  answer: string;
  grounded: boolean;
  citations: Citation[];
  model: string;
  contextChunks: number;
  remainingQuestions: number;
};

export type Suggestion = { key: string; weight: number };

export type MemoryKind = "DECISION" | "TASK" | "FIX" | "CONTEXT";
export type MemoryStatus = "ACTIVE" | "SUPERSEDED" | "DONE";

export type Memory = {
  id: string;
  kind: MemoryKind;
  status: MemoryStatus;
  title: string;
  body: string | null;
  rationale: string | null;
  rejectedOptions: string[];
  supersededBy: string | null;
  sourceMessageId: string | null;
  conversationId: string | null;
  conversationTitle: string | null;
  createdAt: string;
};

export type DriftAlert = {
  key: string;
  type: "DECISION_CONFLICT" | "USAGE_DRIFT";
  severity: "HIGH" | "MEDIUM";
  decisionId: string;
  decisionTitle: string;
  decidedAt: string;
  conflictingDecisionId: string | null;
  evidence: {
    chunkId: string;
    conversationId: string;
    conversationTitle: string;
    sentAt: string;
    sentence: string;
    start: number;
    end: number;
  } | null;
  message: string;
};

export type ResumeView = {
  lastSession: { conversationId: string; title: string; lastActivity: string; messages: number } | null;
  whereYouStopped: string[];
  openTasks: { id: string; title: string; createdAt: string; priority: number }[];
  decisions: Memory[];
  fixes: Memory[];
  driftAlerts: number;
};

export type ContextPack = { markdown: string; estimatedTokens: number; budget: number };

export type TokenInfo = { id: string; name: string; prefix: string; createdAt: string; lastUsedAt: string | null };

export type NewToken = { id: string; name: string; token: string; prefix: string; createdAt: string };

export type IndexInfo = {
  version: number;
  stats: {
    chunks: number;
    conversations: number;
    indexedChars: number;
    autocompleteKeys: number;
    threads: number;
    buildMillis: number;
    builtAt: string;
  };
};

export type Thread = { threadId: string; conversations: { id: string; title: string }[] };

export type CompareOption = { id: string; name: string; note: string | null };
export type CompareCriterion = { id: string; name: string; weight: number };
export type ComparisonMatrix = {
  question: string;
  options: CompareOption[];
  criteria: CompareCriterion[];
  scores: Record<string, Record<string, number>>;
};

export type ComparisonAnalysis = {
  standings: { optionId: string; name: string; percent: number; rank: number }[];
  winnerIds: string[];
  robustness: { optionId: string; name: string; share: number }[];
  samples: number;
  decisive: { criterionId: string; name: string; points: number }[];
  unscored: number;
  problems: string[];
};

export type ComparisonView = {
  id: string;
  projectId: string;
  status: "OPEN" | "DECIDED";
  comparison: ComparisonMatrix;
  analysis: ComparisonAnalysis;
  decisionId: string | null;
  chosen: string | null;
  createdAt: string;
  updatedAt: string;
};

export type ComparisonSummary = {
  id: string;
  question: string;
  status: "OPEN" | "DECIDED";
  options: string[];
  leader: string | null;
  chosen: string | null;
  updatedAt: string;
};

export type CodeRole =
  | "ENTRY" | "PAGE" | "LAYOUT" | "API_ROUTE" | "CONTROLLER" | "SERVICE" | "COMPONENT" | "CODE"
  | "TEST" | "SCRIPT" | "CONFIG" | "MIGRATION" | "STYLE" | "DOC" | "DATA";

export type CodeStats = {
  files: number;
  codeFiles: number;
  lines: number;
  languages: Record<string, number>;
  skipped: number;
  truncated: boolean;
  filesWithSecrets: string[];
};

export type CodeFileSummary = {
  path: string;
  role: CodeRole;
  summary: string;
  lines: number;
  module: string;
  route: string | null;
  importedBy: number;
  conflict: boolean;
};

export type CodeFileInfo = {
  path: string;
  language: string | null;
  lines: number;
  role: CodeRole;
  route: string | null;
  summary: string;
  symbols: string[];
  endpoints: string[];
  imports: string[];
  importedBy: string[];
  packages: string[];
  tools: string[];
  module: string;
  secrets: number;
};

export type CodeDecisionLink = {
  kind: "FOLLOWS" | "GOES_AGAINST";
  tool: string;
  decisionId: string;
  decisionTitle: string;
  decidedAt: string | null;
};

export type CodeOverview = {
  id: string;
  label: string;
  source: "cli" | "zip";
  createdAt: string;
  stats: CodeStats;
  modules: { path: string; files: number; lines: number; dependsOn: string[]; usedBy: string[] }[];
  areas: { files: number; modules: string[] }[];
  startHere: CodeFileSummary[];
  entryPoints: { path: string; role: CodeRole; route: string | null; endpoints: string[]; summary: string }[];
  possiblyUnused: CodeFileSummary[];
  conflicts: { path: string; tool: string; decisionId: string; decisionTitle: string; decidedAt: string | null }[];
  tools: { tool: string; files: number; decisionId: string | null; decisionTitle: string | null; kind: "FOLLOWS" | "GOES_AGAINST" | null }[];
};

export type CodeFileView = {
  file: CodeFileInfo;
  affected: string[];
  decisions: CodeDecisionLink[];
  mentions: {
    matched: string;
    conversationId: string;
    conversationTitle: string;
    sentAt: string;
    role: string;
    text: string;
    decision: boolean;
    replyRole: string | null;
    replyText: string | null;
  }[];
  mapLabel: string;
  mapCreatedAt: string;
};

export type CodeUpload = { id: string; label: string; source: string; createdAt: string; stats: CodeStats };

// ------------------------------------------------------------------ review

export type ReviewSeverity = "high" | "medium" | "low";
export type ReviewCategory = "bug" | "security" | "performance" | "maintainability" | "testing" | "decision" | "other";

export type ReviewIssue = {
  severity: ReviewSeverity;
  category: ReviewCategory;
  title: string;
  file: string | null;
  line: number | null;
  detail: string | null;
  recommendation: string | null;
  /** "model": written by Claude; "agent": by Claude Code; "rules": Notch's automated checks. */
  source: "model" | "agent" | "rules";
};

export type ReviewReport = {
  summary: string | null;
  areas: { name: string; rating: "good" | "fair" | "weak"; assessment: string }[];
  strengths: string[];
  issues: ReviewIssue[];
  recommendations: { priority: "now" | "soon" | "later"; title: string; detail: string | null }[];
  coverage: { reviewer: string; filesTotal: number; filesReviewed: number; linesReviewed: number; note: string | null } | null;
};

export type ReviewSummary = {
  id: string;
  label: string;
  source: "zip" | "claude-code";
  status: "PENDING" | "DONE" | "FAILED";
  error: string | null;
  issues: number;
  highIssues: number;
  createdAt: string;
  finishedAt: string | null;
};

/** A past session, commit or chat about the same thing as a review issue. */
export type SeenBefore = {
  term: string;
  conversationId: string;
  conversationTitle: string | null;
  sentAt: string;
  excerpt: string;
  decision: boolean;
  related: number;
};

export type ReviewDetail = ReviewSummary & { report: ReviewReport | null; seenBefore: SeenBefore[][] };

// ------------------------------------------------------------------ health

export type HealthLevel = "ok" | "watch" | "risk" | "none";
export type HealthRisk = {
  key: string;
  title: string;
  level: HealthLevel;
  headline: string;
  evidence: { text: string; detail: string | null; link: string | null }[];
  action: string;
  actionLink: string;
};
export type RecapItem = { text: string; when: string; detail: string | null };
export type Health = {
  risks: HealthRisk[];
  recap: { done: RecapItem[]; open: RecapItem[]; forgotten: RecapItem[] };
  computedAt: string;
};
