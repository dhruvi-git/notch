export type Theme = "light" | "dark" | "system";

export const THEME_COOKIE = "notch_theme";

export function parseTheme(value: string | undefined): Theme {
  return value === "light" || value === "dark" ? value : "system";
}
