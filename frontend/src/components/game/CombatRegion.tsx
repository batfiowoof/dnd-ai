"use client";

import type { SpellSummary } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import { useCombatSpells } from "@/hooks/useCombatReference";
import { D20Mark } from "@/components/ui";
import CombatTracker from "@/components/combat/CombatTracker";
import BattleMap from "@/components/combat/BattleMap";
import type { PlacingSpell } from "@/components/combat/BattleMap";

interface CombatRegionProps {
  /** Which slice to render: the top tracker, or the left-column battle map. */
  part: "tracker" | "map";
  /** The local player's id (from session storage). */
  playerId: string;
  isCreator: boolean;
  /* ── cast / AoE / targeting sub-state (owned by useCombatInteraction) ── */
  castingSpell: SpellSummary | null;
  pickedTargets: string[];
  placingSpell: PlacingSpell | null;
  onBeginCast: (spell: SpellSummary) => void;
  onSelectTarget: (refId: string) => void;
  onConfirmCast: () => void;
  onCancelCast: () => void;
  onCancelAoe: () => void;
  onCastAoe: (
    spellName: string,
    spellLevel: number,
    x: number,
    y: number
  ) => void;
  /* ── combat action handlers (guarded WS sends) ── */
  onCombatAttack: (enemyId: string) => void;
  onCombatUseItem: (itemName: string) => void;
  onStabilize: (targetPlayerId: string) => void;
  onEndTurn: () => void;
  onEndCombat: () => void;
  onDash: () => void;
  onDisengage: () => void;
  onDodge: () => void;
  onOffHandAttack: (enemyId: string) => void;
  onSecondWind: () => void;
  onCunningAction: (action: "dash" | "disengage" | "hide") => void;
  onMove: (x: number, y: number) => void;
  onUploadMap: (file: File) => Promise<void>;
}

/**
 * The combat block in the chat column: the "preparing the battlefield" loader (while an
 * encounter spins up) and, once combat is live, the CombatTracker (initiative + HP + the
 * active player's controls) and the tactical BattleMap. Subscribes to the store for the
 * combat state, roster, and runtime stats; derives speed / allies / spells locally and
 * absorbs the large combat prop pass-through so the page stays thin.
 */
export default function CombatRegion({
  part,
  playerId,
  isCreator,
  castingSpell,
  pickedTargets,
  placingSpell,
  onBeginCast,
  onSelectTarget,
  onConfirmCast,
  onCancelCast,
  onCancelAoe,
  onCastAoe,
  onCombatAttack,
  onCombatUseItem,
  onStabilize,
  onEndTurn,
  onEndCombat,
  onDash,
  onDisengage,
  onDodge,
  onOffHandAttack,
  onSecondWind,
  onCunningAction,
  onMove,
  onUploadMap,
}: CombatRegionProps) {
  const players = useSessionStore((s) => s.players);
  const connected = useSessionStore((s) => s.connected);
  const combat = useSessionStore((s) => s.combat);
  const combatInitializing = useSessionStore((s) => s.combatInitializing);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);

  const humanPlayers = players.filter((p) => p.role === "PLAYER");
  const inCombat = combat?.status === "ACTIVE";
  const myState = playerId ? runtimeByPlayerId[playerId] ?? null : null;
  const myPlayer = humanPlayers.find((p) => p.id === playerId);
  const combatIsMyTurn =
    inCombat &&
    combat?.active?.kind === "PLAYER" &&
    combat.active.refId === playerId;

  // Walk speed for the move preview — from the character sheet, else 30.
  const mySpeed =
    Number((myPlayer?.characterSheet?.speed as number | undefined) ?? 30) || 30;

  // Lowercased class, to gate class bonus actions (Second Wind / Cunning Action).
  const myClass = String(
    (myPlayer?.characterSheet?.characterClass as string | undefined) ?? ""
  ).toLowerCase();

  // Spell metadata for the in-combat cast menu (fetched once when a fight starts).
  // Only the tracker part needs it — don't double-fetch from the map instance.
  const combatSpellsQuery = useCombatSpells(part === "tracker" && !!inCombat);
  const combatSpells = combatSpellsQuery.data ?? [];
  // Party HP bars — used as heal/buff targets during combat.
  const combatAllies = humanPlayers.map((p) => {
    const rs = runtimeByPlayerId[p.id];
    return {
      id: p.id,
      name: p.characterName ?? p.username,
      currentHp: rs?.currentHp ?? 0,
      maxHp: rs?.maxHp ?? 0,
    };
  });

  /* ── Battle map: the left column (only when combat has a grid) ── */
  if (part === "map") {
    if (!inCombat || !combat?.grid) return null;
    return (
      <div className="min-h-0 overflow-y-auto border-b border-border-accent bg-accent-glow/20 p-3 lg:border-b-0 lg:border-r">
        <BattleMap
          combat={combat}
          myPlayerId={playerId}
          isMyTurn={!!combatIsMyTurn}
          mySpeed={mySpeed}
          runtimeByPlayerId={runtimeByPlayerId}
          onMove={onMove}
          onAttackEnemy={onCombatAttack}
          connected={connected}
          isHost={isCreator}
          placingSpell={placingSpell}
          onCastAoe={onCastAoe}
          onCancelAoe={onCancelAoe}
          castingSpell={castingSpell}
          pickedTargets={pickedTargets}
          onSelectTarget={onSelectTarget}
          onUploadMap={onUploadMap}
        />
      </div>
    );
  }

  /* ── Tracker: the full-width block across the top ── */
  return (
    <>
      {/* Combat initializing — the encounter is being set up (scene + initiative). */}
      {combatInitializing && !inCombat && (
        <div className="flex items-center justify-center gap-3 border-b border-border-accent bg-accent-glow/20 px-4 py-6 text-accent animate-rise">
          <D20Mark className="h-6 w-6 animate-spin" />
          <span className="font-display text-sm font-bold uppercase tracking-wider">
            Preparing the battlefield…
          </span>
        </div>
      )}
      {inCombat && combat && (
        <div className="flex flex-none flex-col">
          <CombatTracker
            combat={combat}
            myPlayerId={playerId}
            myState={myState}
            mySpeed={mySpeed}
            isHost={isCreator}
            connected={connected}
            spells={combatSpells}
            allies={combatAllies}
            runtimeByPlayerId={runtimeByPlayerId}
            onAttack={onCombatAttack}
            onBeginCast={onBeginCast}
            castingSpell={castingSpell}
            pickedTargets={pickedTargets}
            onSelectTarget={onSelectTarget}
            onConfirmCast={onConfirmCast}
            onCancelCast={onCancelCast}
            placingSpellName={placingSpell?.name ?? null}
            onCancelAoe={onCancelAoe}
            onUseItem={onCombatUseItem}
            onStabilize={onStabilize}
            onEndTurn={onEndTurn}
            onEndCombat={onEndCombat}
            onDash={onDash}
            onDisengage={onDisengage}
            onDodge={onDodge}
            myClass={myClass}
            onOffHandAttack={onOffHandAttack}
            onSecondWind={onSecondWind}
            onCunningAction={onCunningAction}
          />
        </div>
      )}
    </>
  );
}
