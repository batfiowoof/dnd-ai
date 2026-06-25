"use client";

import {
  useState,
  useId,
  useRef,
  useCallback,
  useLayoutEffect,
  useEffect,
} from "react";
import { createPortal } from "react-dom";
import { cn } from "./cn";

type Placement = "top" | "right" | "bottom" | "left";

interface TooltipProps {
  /** Tooltip body. Kept lightweight — a few lines of stats. */
  content: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  /** Preferred side; auto-flips to the opposite side if it would overflow. */
  placement?: Placement;
}

const GAP = 8;
const MARGIN = 8;

/**
 * Hover/focus tooltip rendered through a portal on `document.body` with fixed,
 * viewport-measured positioning. Portalling escapes any `overflow:hidden/auto`
 * ancestor (e.g. the scrollable players sidebar) that would otherwise clip it,
 * and the collision flip keeps it inside the screen on every edge. Appears on
 * pointer hover AND keyboard focus; for always-accessible data, pair the trigger
 * with a click that opens a dialog.
 */
export default function Tooltip({
  content,
  children,
  className,
  placement = "right",
}: TooltipProps) {
  const [open, setOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(
    null
  );
  const triggerRef = useRef<HTMLSpanElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const id = useId();

  useEffect(() => setMounted(true), []);

  const position = useCallback(() => {
    const trigger = triggerRef.current;
    const tip = tipRef.current;
    if (!trigger || !tip) return;

    const t = trigger.getBoundingClientRect();
    const tw = tip.offsetWidth;
    const th = tip.offsetHeight;
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // Flip to the opposite side when the preferred side would overflow.
    let place = placement;
    if (place === "right" && t.right + GAP + tw > vw - MARGIN) place = "left";
    else if (place === "left" && t.left - GAP - tw < MARGIN) place = "right";
    else if (place === "top" && t.top - GAP - th < MARGIN) place = "bottom";
    else if (place === "bottom" && t.bottom + GAP + th > vh - MARGIN)
      place = "top";

    let top = 0;
    let left = 0;
    switch (place) {
      case "right":
        left = t.right + GAP;
        top = t.top + t.height / 2 - th / 2;
        break;
      case "left":
        left = t.left - GAP - tw;
        top = t.top + t.height / 2 - th / 2;
        break;
      case "top":
        top = t.top - GAP - th;
        left = t.left + t.width / 2 - tw / 2;
        break;
      case "bottom":
        top = t.bottom + GAP;
        left = t.left + t.width / 2 - tw / 2;
        break;
    }

    // Clamp fully into the viewport on the cross axis.
    left = Math.max(MARGIN, Math.min(left, vw - tw - MARGIN));
    top = Math.max(MARGIN, Math.min(top, vh - th - MARGIN));
    setCoords({ top, left });
  }, [placement]);

  // Measure & place once the tip is in the DOM, then keep it pinned while open.
  useLayoutEffect(() => {
    if (!open) {
      setCoords(null);
      return;
    }
    position();
    const onScroll = () => position();
    window.addEventListener("scroll", onScroll, true);
    window.addEventListener("resize", onScroll);
    return () => {
      window.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", onScroll);
    };
  }, [open, position]);

  return (
    <span
      ref={triggerRef}
      className={cn("relative inline-flex", className)}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      aria-describedby={open ? id : undefined}
    >
      {children}
      {open &&
        mounted &&
        createPortal(
          <span
            ref={tipRef}
            role="tooltip"
            id={id}
            style={{
              top: coords?.top ?? 0,
              left: coords?.left ?? 0,
              // Hidden until measured to avoid a one-frame flash at (0,0).
              visibility: coords ? "visible" : "hidden",
            }}
            className={cn(
              "pointer-events-none fixed z-[1000] min-w-max max-w-xs",
              "rounded-lg border border-border-accent bg-surface px-3 py-2",
              "text-left shadow-[0_0_20px_var(--color-accent-glow)] animate-rise"
            )}
          >
            {content}
          </span>,
          document.body
        )}
    </span>
  );
}
