import type { CodeRole } from "./types";

export const ROLE_LABEL: Record<CodeRole, string> = {
  ENTRY: "Entry point",
  PAGE: "Page",
  LAYOUT: "Layout",
  API_ROUTE: "API route",
  CONTROLLER: "API controller",
  SERVICE: "Framework-managed",
  COMPONENT: "Component",
  CODE: "Code",
  TEST: "Tests",
  SCRIPT: "Script",
  CONFIG: "Configuration",
  MIGRATION: "Migration",
  STYLE: "Styles",
  DOC: "Docs",
  DATA: "Data",
};

export function fileHref(projectId: string, path: string) {
  return `/p/${projectId}/explain/file?path=${encodeURIComponent(path)}`;
}

/** "src/billing/invoices.ts" → ["src/billing/", "invoices.ts"] */
export function splitPath(path: string): [string, string] {
  const i = path.lastIndexOf("/");
  return i < 0 ? ["", path] : [path.slice(0, i + 1), path.slice(i + 1)];
}
