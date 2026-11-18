"use client";

import { useEffect, useState } from "react";
import styles from "./profile.module.css";

type Props = { handle: string; headline: string; subline: string; detail: string };

/** The yellow card for LinkedIn and Instagram: copy the profile link, or download the card as an image. */
export function ShareCard({ handle, headline, subline, detail }: Props) {
  const [copied, setCopied] = useState(false);
  // The address is known only in the browser; render the path first so server and client agree.
  const [origin, setOrigin] = useState("");
  useEffect(() => setOrigin(window.location.origin), []);
  const url = `${origin}/u/${handle}`;
  const shown = url.replace(/^https?:\/\//, "");

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  }

  /** Draws the card at 1080×1350 (a 4:5 post) and downloads it as a PNG. */
  async function download() {
    const w = 1080;
    const h = 1350;
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    await document.fonts.ready;
    const display = getComputedStyle(document.documentElement).getPropertyValue("--font-display") || "sans-serif";
    const sans = getComputedStyle(document.documentElement).getPropertyValue("--font-sans") || "sans-serif";
    ctx.fillStyle = "#fedc2a";
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = "#2f253a";
    ctx.font = `800 64px ${display}`;
    ctx.fillText("notch", 96, 160);
    ctx.font = `800 190px ${display}`;
    ctx.fillText(headline, 90, 620);
    ctx.font = `600 64px ${sans}`;
    wrap(ctx, subline, 96, 730, w - 192, 80);
    ctx.font = `500 48px ${sans}`;
    ctx.fillText(detail, 96, h - 210);
    ctx.font = `500 44px ${sans}`;
    ctx.fillText(shown, 96, h - 130);
    const link = document.createElement("a");
    link.download = `notch-${handle}.png`;
    link.href = canvas.toDataURL("image/png");
    link.click();
  }

  return (
    <div>
      <div className={styles.shareCard}>
        <p className={styles.shareBrand}>notch</p>
        <p className={styles.shareHeadline}>{headline}</p>
        <p className={styles.shareSub}>{subline}</p>
        <p className={styles.shareDetail}>{detail}</p>
        <p className={styles.shareUrl}>{shown}</p>
      </div>
      <div className={styles.shareActions}>
        <button type="button" className="btn btn-yellow" onClick={download}>
          Download card
        </button>
        <button type="button" className="btn btn-quiet" onClick={copy}>
          {copied ? "Link copied" : "Copy link"}
        </button>
      </div>
    </div>
  );
}

function wrap(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, max: number, lineHeight: number) {
  let line = "";
  for (const word of text.split(" ")) {
    const next = line ? `${line} ${word}` : word;
    if (ctx.measureText(next).width > max && line) {
      ctx.fillText(line, x, y);
      line = word;
      y += lineHeight;
    } else {
      line = next;
    }
  }
  if (line) ctx.fillText(line, x, y);
}
