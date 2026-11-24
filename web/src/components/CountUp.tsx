"use client";

import { useEffect, useRef, useState } from "react";

/**
 * A number that counts up from 0 when it first scrolls into view. The final value is what's
 * rendered on the server and read by screen readers; with reduced motion it just appears.
 */
export function CountUp({ value, decimals = 0, duration = 900, delay = 0 }: { value: number; decimals?: number; duration?: number; delay?: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  const [shown, setShown] = useState(value);

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof window === "undefined") return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches || value === 0) {
      setShown(value);
      return;
    }
    let frame = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const run = () => {
      const start = performance.now();
      const tick = (now: number) => {
        const t = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - t, 3);
        setShown(value * eased);
        if (t < 1) frame = requestAnimationFrame(tick);
      };
      frame = requestAnimationFrame(tick);
    };
    setShown(0);
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          io.disconnect();
          timer = setTimeout(run, delay);
        }
      },
      { threshold: 0.4 },
    );
    io.observe(el);
    return () => {
      io.disconnect();
      if (timer) clearTimeout(timer);
      cancelAnimationFrame(frame);
    };
  }, [value, duration, delay]);

  const text = shown.toFixed(decimals);
  return (
    <span ref={ref} aria-label={value.toFixed(decimals)}>
      <span aria-hidden="true">{text}</span>
    </span>
  );
}
