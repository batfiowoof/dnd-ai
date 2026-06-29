"use client";

import type { RefObject } from "react";
import type { PlayerDto } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import { D20Mark } from "@/components/ui";
import Portrait from "@/components/Portrait";
import { stripDmTags } from "@/lib/strip";

interface GameLogProps {
  /** Resolve a log entry's author (by character name / username / id) for the portrait. */
  playerByName: (nameOrId?: string) => PlayerDto | null;
  /** The scroll container ref (page owns it for scroll-to-bottom). */
  scrollRef: RefObject<HTMLDivElement | null>;
}

/**
 * The scrolling chat log: player action lines (with portrait), DM narration cards, dice-roll
 * transcript lines, and neutral system lines, plus the "DM is weaving the tale" thinking
 * indicator and the empty-state hint. Subscribes to the store for `logs` / `dmThinking`.
 */
export default function GameLog({ playerByName, scrollRef }: GameLogProps) {
  const logs = useSessionStore((s) => s.logs);
  const dmThinking = useSessionStore((s) => s.dmThinking);

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-3">
      {logs.map((entry) => (
        <div key={entry.id} className="animate-rise">
          {entry.type === "action" && (
            <div className="flex items-start gap-2">
              <Portrait
                src={playerByName(entry.playerName)?.imageUrl}
                name={entry.playerName}
                size="xs"
                className="mt-0.5"
              />
              <div className="flex flex-wrap gap-x-2">
                <span className="text-xs font-semibold text-gold">
                  {entry.playerName}:
                </span>
                <span className="text-sm text-text">{entry.text}</span>
              </div>
            </div>
          )}
          {entry.type === "dm" && (
            <div className="ml-4 rounded-lg border border-border-accent bg-accent-glow px-4 py-3 shadow-[0_0_24px_var(--color-accent-glow)]">
              <span className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-accent">
                <D20Mark className="h-3.5 w-3.5" />
                Dungeon Master
              </span>
              <p className="text-sm leading-relaxed text-text">
                {stripDmTags(entry.text)}
              </p>
            </div>
          )}
          {entry.type === "roll" && (
            <div className="flex items-center justify-center gap-2 text-xs text-text-muted">
              <D20Mark className="h-3.5 w-3.5 text-gold" />
              <span>
                <span className="font-medium text-gold">
                  {entry.playerName}
                </span>{" "}
                {entry.text}
              </span>
            </div>
          )}
          {entry.type === "system" && (
            <p className="text-center text-xs italic text-text-muted">
              {entry.text}
            </p>
          )}
        </div>
      ))}
      {dmThinking && (
        <div className="ml-4 flex items-center gap-2 text-xs text-accent animate-rise">
          <D20Mark className="h-3.5 w-3.5 animate-spin" />
          <span className="italic">The Dungeon Master is weaving the tale</span>
          <span className="inline-flex gap-0.5">
            <span className="h-1 w-1 animate-bounce rounded-full bg-accent [animation-delay:-0.3s]" />
            <span className="h-1 w-1 animate-bounce rounded-full bg-accent [animation-delay:-0.15s]" />
            <span className="h-1 w-1 animate-bounce rounded-full bg-accent" />
          </span>
        </div>
      )}
      {logs.length === 0 && !dmThinking && (
        <p className="text-center text-sm text-text-muted">
          Waiting for the adventure to begin...
        </p>
      )}
    </div>
  );
}
