"use client";

import { useSessionStore } from "@/store/sessionStore";
import { formatGameClock } from "@/lib/travel";
import { cn } from "@/components/ui";

/**
 * Header chip showing the party's current location and the in-game clock ("Day N · HH:MM").
 * Renders nothing for sessions without a placed location (free-text worlds), so it only appears
 * when travel is in play.
 */
export default function InGameClock({ className }: { className?: string }) {
  const currentRegion = useSessionStore((s) => s.currentRegion);
  const inGameMinutes = useSessionStore((s) => s.inGameMinutes);

  if (!currentRegion) return null;

  return (
    <span
      className={cn(
        "hidden items-center gap-1.5 rounded-full border border-gold/40 bg-gold-muted px-2.5 py-0.5 text-[11px] text-gold sm:inline-flex",
        className
      )}
      title="Current location and in-game time"
    >
      <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <span className="max-w-[10rem] truncate font-medium text-text">{currentRegion}</span>
      <span aria-hidden="true" className="text-gold/50">·</span>
      <span className="tabular">{formatGameClock(inGameMinutes)}</span>
    </span>
  );
}
