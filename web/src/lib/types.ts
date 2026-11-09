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

export type AuthConfig = { github: boolean; devLogin: boolean };

export type Session = { token: string; expiresAt: string; user: User };

/** RFC 9457 problem response with the API's stable error code. */
export type Problem = { status: number; title?: string; detail?: string; code?: string };
