import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import "@fontsource/schibsted-grotesk/400.css";
import "@fontsource/schibsted-grotesk/500.css";
import "@fontsource/schibsted-grotesk/600.css";
import "@fontsource/schibsted-grotesk/700.css";
import "@fontsource/fragment-mono/400.css";
import "./globals.css";
import { THEME_COOKIE, parseTheme } from "@/lib/theme";

export const metadata: Metadata = {
  title: { default: "Notch", template: "%s | Notch" },
  description: "The decision record for AI-assisted projects.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#edeff5" },
    { media: "(prefers-color-scheme: dark)", color: "#171b30" },
  ],
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // The saved theme is applied on the server, so the page never flashes the wrong one.
  const theme = parseTheme((await cookies()).get(THEME_COOKIE)?.value);
  return (
    <html lang="en" data-theme={theme === "system" ? undefined : theme}>
      <body>{children}</body>
    </html>
  );
}
