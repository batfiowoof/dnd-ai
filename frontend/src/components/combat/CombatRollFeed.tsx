"use client";

import { useEffect, useState } from "react";
import { useSessionStore, type FeedEntry } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import { cn } from "@/components/ui";

/** How many feed lines to show docked on the map at once (store keeps a few more). */
const VISIBLE = 6;

/** Reduced-motion: OS setting OR the in-app preference flag (matches BattleMap). */
function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    const read = () =>
      setReduced(
        !!mq?.matches ||
          document.documentElement.dataset.reduceMotion === "true"
      );
    read();
    mq?.addEventListener?.("change", read);
    return () => mq?.removeEventListener?.("change", read);
  }, []);
  return reduced;
}

const TONE_TEXT: Record<FeedEntry["tone"], string> = {
  good: "text-success",
  bad: "text-danger",
  neutral: "text-text-muted",
};
const TONE_BORDER: Record<FeedEntry["tone"], string> = {
  good: "border-success/40",
  bad: "border-danger/40",
  neutral: "border-border",
};

function FeedCard({ entry, reduced }: { entry: FeedEntry; reduced: boolean }) {
  return (
    <div
      className={cn(
        "flex items-center gap-2 rounded-lg border bg-bg-elevated/85 px-2 py-1.5 shadow-sm backdrop-blur-sm",
        // A boss acting out of turn is its own visual family: gold rail instead of the tone border.
        entry.boss
          ? "border-gold/50 border-l-2 border-l-gold bg-gold/5"
          : TONE_BORDER[entry.tone],
        !reduced && "animate-rise"
      )}
    >
      {entry.roll ? (
        <Die
          value={entry.roll.total}
          sides={entry.roll.sides}
          rolling={false}
          crit={entry.roll.crit}
          fumble={entry.roll.fumble}
          size={26}
        />
      ) : (
        <span
          aria-hidden
          className="grid h-[26px] w-[26px] shrink-0 place-items-center text-gold"
        >
          {entry.boss ? "⚜" : "✦"}
        </span>
      )}

      <div className="min-w-0 flex-1 leading-tight">
        <div className="truncate text-[11px]">
          <span className={cn("font-semibold", entry.boss ? "text-gold" : "text-text")}>
            {entry.actorName}
          </span>{" "}
          <span className="text-text-muted">{entry.title}</span>
        </div>
        <div className="flex items-center gap-1.5">
          {entry.outcome && (
            <span
              className={cn(
                "text-[10px] font-bold uppercase tracking-wide",
                // The outcome still reads from the party's POV; only a toneless beat goes gold.
                entry.boss && entry.tone === "neutral" ? "text-gold" : TONE_TEXT[entry.tone]
              )}
            >
              {entry.outcome}
            </span>
          )}
          {entry.detail && (
            <span className="tabular truncate text-[10px] text-text-muted">
              {entry.detail}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Compact, non-blocking roll feed docked on the battle map (top-right). Shows the
 * most recent enemy attacks, NPC rolls, and other players' rolls as they resolve —
 * the things that would otherwise spam the screen-centre dice modal. The local
 * player's OWN dramatic rolls still use the big modal (routed in the store). The
 * overlay is `pointer-events-none` so it never blocks clicking tokens/cells beneath.
 */
export default function CombatRollFeed() {
  const feed = useSessionStore((s) => s.combatFeed);
  const reduced = usePrefersReducedMotion();

  if (feed.length === 0) return null;

  return (
    <div
      className="pointer-events-none absolute right-2 top-2 z-10 flex w-44 flex-col gap-1.5"
      aria-live="polite"
      aria-label="Combat roll feed"
    >
      {feed.slice(0, VISIBLE).map((entry, i) => (
        <div
          key={entry.id}
          style={{ opacity: i >= VISIBLE - 2 ? 0.6 : 1 }}
        >
          <FeedCard entry={entry} reduced={reduced} />
        </div>
      ))}
    </div>
  );
}
