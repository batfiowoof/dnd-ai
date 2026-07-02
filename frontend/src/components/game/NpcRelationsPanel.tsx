"use client";

import { useSessionStore } from "@/store/sessionStore";
import { bandFromScore } from "@/lib/dispositions";

/**
 * Compact list of the campaign's NPCs and how each currently feels about the party, updating live as
 * the DM shifts relationships. Each row pairs an icon with the band label so attitude never reads by
 * colour alone. Renders nothing when there are no tracked NPCs (e.g. free-text worlds).
 */
export default function NpcRelationsPanel() {
  const npcStates = useSessionStore((s) => s.npcStates);

  if (npcStates.length === 0) return null;

  return (
    <div className="mt-4 border-t border-border pt-3">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
        Relationships
      </h2>
      <ul className="space-y-1.5">
        {npcStates.map((npc) => {
          const band = bandFromScore(npc.disposition);
          return (
            <li key={npc.name} className="flex items-center gap-2 text-xs">
              <span
                aria-hidden
                className="flex h-4 w-4 flex-shrink-0 items-center justify-center text-[11px]"
                style={{ color: band.accent }}
              >
                {band.icon}
              </span>
              <span className="min-w-0 flex-1 truncate text-text">{npc.name}</span>
              <span className="flex-shrink-0 text-[10px]" style={{ color: band.accent }}>
                {band.label}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
