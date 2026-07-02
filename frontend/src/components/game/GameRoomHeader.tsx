"use client";

import { useSessionStore } from "@/store/sessionStore";
import { Brand, cn } from "@/components/ui";
import StartEncounterControl from "@/components/combat/StartEncounterControl";
import AvatarTrigger from "@/components/game/AvatarTrigger";
import InGameClock from "@/components/travel/InGameClock";

interface GameRoomHeaderProps {
  /** The local player's id (from session storage), or null. */
  playerId: string | null;
  isCreator: boolean;
  /** Host-only: kick off an encounter (shown out of combat). */
  onStartEncounter: (enemyKeys: string[]) => void;
  /** Host-only: open the "end adventure?" confirmation. */
  onEndSession: () => void;
  /** Open the "leave session?" confirmation. */
  onLeave: () => void;
  /** Open a player's character sheet. */
  onOpenSheet: (playerId: string) => void;
}

/**
 * Game-room header: brand, finished badge, host-only Start Encounter control, the
 * mode/combat-aware turn label, the local player's Inspiration token, the connection dot,
 * the Leave button, and the local player's avatar (opens their sheet). Subscribes to the
 * store for all live turn/combat state.
 */
export default function GameRoomHeader({
  playerId,
  isCreator,
  onStartEncounter,
  onEndSession,
  onLeave,
  onOpenSheet,
}: GameRoomHeaderProps) {
  const players = useSessionStore((s) => s.players);
  const status = useSessionStore((s) => s.status);
  const connected = useSessionStore((s) => s.connected);
  const dmThinking = useSessionStore((s) => s.dmThinking);
  const currentTurnPlayerId = useSessionStore((s) => s.currentTurnPlayerId);
  const turnNumber = useSessionStore((s) => s.turnNumber);
  const turnMode = useSessionStore((s) => s.turnMode);
  const round = useSessionStore((s) => s.round);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);
  const combat = useSessionStore((s) => s.combat);

  const humanPlayers = players.filter((p) => p.role === "PLAYER");
  const inCombat = combat?.status === "ACTIVE";
  const combatActive = inCombat ? combat?.active ?? null : null;
  const currentPlayer = humanPlayers.find((p) => p.id === currentTurnPlayerId);
  const myState = playerId ? runtimeByPlayerId[playerId] ?? null : null;
  const myPlayer = humanPlayers.find((p) => p.id === playerId);

  // Header status line, mode/combat aware.
  const activeLabel = inCombat
    ? combatActive
      ? `${combatActive.name}'s turn`
      : null
    : turnMode === "INITIATIVE"
      ? currentPlayer
        ? `${currentPlayer.characterName}'s turn`
        : null
      : turnMode === "COLLABORATIVE"
        ? round?.open
          ? "Round collecting…"
          : dmThinking
            ? "DM is resolving the round…"
            : null
        : dmThinking
          ? "DM is responding…"
          : null;

  return (
    <header className="flex items-center justify-between border-b border-border bg-surface/80 px-4 py-3 backdrop-blur-sm">
      <div className="flex items-center gap-3">
        <Brand size="sm" />
        {status === "FINISHED" && (
          <span className="rounded bg-gold-muted px-2 py-0.5 text-[10px] uppercase tracking-wider text-gold">
            Finished
          </span>
        )}
      </div>
      <div className="flex items-center gap-4">
        {/* Current location + in-game clock (only for world-based, out-of-combat sessions). */}
        {!inCombat && <InGameClock />}
        {isCreator && status === "ACTIVE" && !inCombat && (
          <StartEncounterControl
            connected={connected}
            onStart={onStartEncounter}
          />
        )}
        <span className="text-xs text-text-muted">
          <span className="tabular text-gold">Turn {turnNumber}</span>
          {activeLabel && <> &middot; {activeLabel}</>}
        </span>
        {/* Inspiration token — the local player holds spendable Inspiration. */}
        {myState?.inspiration && (
          <span
            title="You have Inspiration — spend it on a roll for advantage"
            className="inline-flex items-center gap-1 rounded-full border border-gold/50 bg-gold-muted px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-gold"
          >
            <svg
              viewBox="0 0 24 24"
              width="11"
              height="11"
              fill="currentColor"
              aria-hidden="true"
            >
              <path d="M12 2l1.9 5.6a3 3 0 0 0 1.9 1.9L21.5 11l-5.7 1.9a3 3 0 0 0-1.9 1.9L12 20.5l-1.9-5.7a3 3 0 0 0-1.9-1.9L2.5 11l5.7-1.9a3 3 0 0 0 1.9-1.9L12 2z" />
            </svg>
            Inspiration
          </span>
        )}

        <span
          className={cn(
            "h-2 w-2 rounded-full",
            connected ? "bg-success" : "bg-danger"
          )}
          title={connected ? "Connected" : "Disconnected"}
        />

        {isCreator && status === "ACTIVE" && !inCombat && (
          <button
            type="button"
            onClick={onEndSession}
            title="End the adventure and write its recap"
            className="rounded-md border border-gold/40 px-2.5 py-1 text-xs font-semibold text-gold transition hover:border-gold hover:bg-gold/10"
          >
            End Adventure
          </button>
        )}

        <button
          type="button"
          onClick={onLeave}
          title="Leave this session"
          className="rounded-md border border-border px-2.5 py-1 text-xs font-semibold text-text-muted transition hover:border-danger/60 hover:text-danger"
        >
          Leave
        </button>

        {/* My character — always reachable (the sidebar is hidden below md) */}
        {myPlayer && (
          <AvatarTrigger
            player={myPlayer}
            state={myState}
            placement="bottom"
            onOpen={() => onOpenSheet(myPlayer.id)}
          />
        )}
      </div>
    </header>
  );
}
