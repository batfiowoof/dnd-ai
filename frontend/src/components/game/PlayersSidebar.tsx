"use client";

import { useMemo } from "react";
import { useSessionStore } from "@/store/sessionStore";
import { D20Mark, HpBar, cn } from "@/components/ui";
import AvatarTrigger from "@/components/game/AvatarTrigger";
import InitiativeChip from "@/components/game/InitiativeChip";
import NpcRelationsPanel from "@/components/game/NpcRelationsPanel";

interface PlayersSidebarProps {
  /** Open a player's character sheet. */
  onOpenSheet: (playerId: string) => void;
}

/**
 * Left party sidebar (md+): each player's avatar (hover for HP/AC, click for sheet), the
 * active-turn / active-combatant highlight, the per-combatant initiative chip during combat,
 * and an HP bar. Subscribes to the store for the roster, runtime stats, and turn/combat state.
 */
export default function PlayersSidebar({ onOpenSheet }: PlayersSidebarProps) {
  const players = useSessionStore((s) => s.players);
  const createdBy = useSessionStore((s) => s.createdBy);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);
  const combat = useSessionStore((s) => s.combat);
  const turnMode = useSessionStore((s) => s.turnMode);
  const currentTurnPlayerId = useSessionStore((s) => s.currentTurnPlayerId);

  const humanPlayers = players.filter((p) => p.role === "PLAYER");
  const inCombat = combat?.status === "ACTIVE";
  const combatActive = inCombat ? combat?.active ?? null : null;

  // Who the sidebar should highlight as "active" right now.
  const highlightedPlayerId = inCombat
    ? combatActive?.kind === "PLAYER"
      ? combatActive.refId
      : null
    : turnMode === "INITIATIVE"
      ? currentTurnPlayerId
      : null;

  // Initiative value per combatant ref id (for the d20 chips during combat).
  const initiativeByRefId = useMemo(() => {
    const m: Record<string, number> = {};
    combat?.order.forEach((c) => {
      m[c.refId] = c.initiative;
    });
    return m;
  }, [combat]);

  return (
    <aside className="hidden w-56 flex-shrink-0 overflow-y-auto border-r border-border bg-surface/60 p-4 md:flex md:flex-col">
      <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-text-muted">
        Players
      </h2>
      <div className="space-y-2">
        {humanPlayers.map((p) => (
          <div
            key={p.id}
            data-spotlight=""
            className={cn(
              "spotlight flex items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-xs transition",
              p.id === highlightedPlayerId
                ? "border border-gold/60 bg-gold-muted text-gold"
                : "border border-transparent text-text-muted hover:border-accent/40"
            )}
          >
            <div className="relative flex-shrink-0">
              <AvatarTrigger
                player={p}
                state={runtimeByPlayerId[p.id]}
                active={p.id === highlightedPlayerId}
                onOpen={() => onOpenSheet(p.id)}
              />
              {inCombat && initiativeByRefId[p.id] !== undefined && (
                <InitiativeChip
                  value={initiativeByRefId[p.id]}
                  active={p.id === highlightedPlayerId}
                />
              )}
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate font-medium">{p.characterName}</div>
              <div className="truncate text-[10px] opacity-60">
                {p.username}
                {p.username === createdBy && " (host)"}
              </div>
              {runtimeByPlayerId[p.id] && (
                <HpBar
                  current={runtimeByPlayerId[p.id].currentHp}
                  max={runtimeByPlayerId[p.id].maxHp}
                  size="sm"
                  className="mt-1"
                />
              )}
            </div>
          </div>
        ))}
      </div>

      {/* Tip: hover an avatar for HP/AC, click for the full sheet. */}
      <p className="mt-4 text-[10px] leading-relaxed text-text-muted">
        Hover a portrait for HP &amp; AC · click for the full sheet.
      </p>

      {/* NPC relationships (hidden when the campaign tracks none) */}
      <NpcRelationsPanel />

      {/* AI DM indicator in sidebar */}
      <div className="mt-auto border-t border-border pt-3">
        <div className="flex items-center gap-2 text-xs text-accent">
          <D20Mark className="h-4 w-4" />
          <span className="font-medium">Dungeon Master</span>
        </div>
        <div className="text-[10px] text-text-muted">AI &middot; Ollama</div>
      </div>
    </aside>
  );
}
