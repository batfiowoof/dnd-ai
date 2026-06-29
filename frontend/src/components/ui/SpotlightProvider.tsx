"use client";

import { useEffect } from "react";
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion";

/**
 * Drives the cursor-following border glow used by `.spotlight` surfaces. A SINGLE
 * rAF-throttled `pointermove` listener (mounted once, app-wide) writes the pointer's
 * position — relative to whichever `[data-spotlight]` element is under it — into that
 * element's `--mx`/`--my` CSS variables. The `.spotlight` rule (globals.css) paints a
 * masked border ring at that point.
 *
 * Delegation avoids attaching a handler to every panel/card. When reduced motion is active
 * (OS setting or the in-app flag) we don't track at all — the CSS falls back to a plain
 * static accent border on hover. Renders nothing.
 */
export default function SpotlightProvider() {
  const reduced = usePrefersReducedMotion();

  useEffect(() => {
    if (reduced) return;

    let frame = 0;
    let pending: PointerEvent | null = null;

    const apply = () => {
      frame = 0;
      const e = pending;
      pending = null;
      if (!e) return;
      const target = e.target as Element | null;
      const el = target?.closest?.("[data-spotlight]") as HTMLElement | null;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      el.style.setProperty("--mx", `${e.clientX - rect.left}px`);
      el.style.setProperty("--my", `${e.clientY - rect.top}px`);
    };

    const onMove = (e: PointerEvent) => {
      pending = e;
      if (!frame) frame = requestAnimationFrame(apply);
    };

    window.addEventListener("pointermove", onMove, { passive: true });
    return () => {
      window.removeEventListener("pointermove", onMove);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [reduced]);

  return null;
}
