import { useMemo } from "react";
import type {
  Combatant,
  CombatStateDto,
  EnemyDto,
  GridState,
  PlayerRuntimeState,
  SpellSummary,
  Token,
} from "@/types";
import {
  aoeCellsInBounds,
  cellKey,
  reachableSquares,
  threatenedSquares,
} from "@/lib/grid";
import {
  isAllyTargeting,
  parseRangeFeet,
  weaponRangeFeet,
} from "@/lib/combat";
import { useSessionStore } from "@/store/sessionStore";
import { CELL } from "./constants";

export interface BattleMapGeometry {
  grid: GridState | null;
  combatantByRef: Record<string, Combatant>;
  activeRefId: string | null;
  myToken: Token | null;
  reachable: Set<string>;
  occupied: Set<string>;
  threatened: Set<string>;
  previewCells: Set<string>;
  enemyById: Record<string, EnemyDto>;
  W: number;
  H: number;
  placing: boolean;
  targeting: boolean;
  interactive: boolean;
  casterTok: Token | undefined;
  targetAlly: boolean;
  targetRangeFeet: number;
  myConds: string[] | undefined;
  myAttackRange: number;
}

interface GeometryArgs {
  combat: CombatStateDto;
  myPlayerId: string;
  isMyTurn: boolean;
  mySpeed: number;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  connected: boolean;
  placingSpell: { aoeShape: string; aoeSize: number } | null;
  castingSpell: SpellSummary | null;
  /** Hovered AoE origin cell ("x,y"), owned by the parent so the preview + capture share it. */
  placeHover: string | null;
}

/**
 * Derives every geometry/context value the battle-map layers consume: combatant lookups, the
 * reachable/occupied/threatened sets, the AoE preview cells, board dimensions, and the
 * spell-targeting/attack context. Subscribes to the session store for the combat-busy flag so
 * interactions are suspended while queued action animations / DM narration resolve.
 */
export function useBattleMapGeometry({
  combat,
  myPlayerId,
  isMyTurn,
  mySpeed,
  runtimeByPlayerId,
  connected,
  placingSpell,
  castingSpell,
  placeHover,
}: GeometryArgs): BattleMapGeometry {
  const grid = combat.grid;

  // refId → combatant (name / kind) for token labelling.
  const combatantByRef = useMemo(() => {
    const m: Record<string, Combatant> = {};
    combat.order.forEach((c) => {
      m[c.refId] = c;
    });
    return m;
  }, [combat.order]);

  const activeRefId = combat.active?.refId ?? null;
  const myToken = grid?.tokens[myPlayerId] ?? null;

  // Reachable highlight (my turn only) — remaining budget = speed − used.
  const reachable = useMemo(() => {
    if (!grid || !isMyTurn || !myToken) return new Set<string>();
    const budget = mySpeed - myToken.movementUsedFeet;
    return reachableSquares(grid, myPlayerId, budget);
  }, [grid, isMyTurn, myToken, mySpeed, myPlayerId]);

  // Occupied cells (can't move onto a token).
  const occupied = useMemo(() => {
    const s = new Set<string>();
    if (grid) {
      for (const t of Object.values(grid.tokens)) s.add(cellKey(t.x, t.y));
    }
    return s;
  }, [grid]);

  // Squares within a living enemy's melee reach — tinted so the player can avoid provoking
  // opportunity attacks when moving out of them.
  const threatened = useMemo(
    () => threatenedSquares(grid, combat.enemies),
    [grid, combat.enemies]
  );

  // Combat is "busy" while queued action animations play or the DM narrates the beat.
  const queueLength = useSessionStore((s) => s.combatActionQueue.length);
  const combatNarrating = useSessionStore((s) => s.combatNarrating);
  const busy = queueLength > 0 || combatNarrating;

  const W = grid ? grid.width * CELL : 0;
  const H = grid ? grid.height * CELL : 0;
  const placing = !!placingSpell;
  const targeting = !!castingSpell;
  // Suspend interactions while aiming an AoE template, or while the previous beat's action
  // animations / DM narration are still resolving (so the player can follow what happened).
  const interactive = isMyTurn && connected && !placing && !busy;
  // Active spell-targeting context (which side is valid + the spell's range in feet).
  const casterTok = grid?.tokens[myPlayerId];
  const targetAlly = castingSpell ? isAllyTargeting(castingSpell) : false;
  const targetRangeFeet = castingSpell ? parseRangeFeet(castingSpell.range) : Infinity;
  // My conditions drive the advantage/disadvantage preview shown on enemy tokens.
  const myConds = runtimeByPlayerId[myPlayerId]?.conditions;
  // My basic-attack range (ft) from my weapons — enemies beyond it can't be attacked.
  const myAttackRange = weaponRangeFeet(runtimeByPlayerId[myPlayerId]?.inventory);

  const enemyById = useMemo(
    () => Object.fromEntries(combat.enemies.map((e) => [e.id, e])) as Record<string, EnemyDto>,
    [combat.enemies]
  );

  // Template cells under the cursor while placing (in-bounds keys only).
  const previewCells = useMemo(() => {
    if (!grid || !placingSpell || !placeHover) return new Set<string>();
    const [ox, oy] = placeHover.split(",").map(Number);
    return aoeCellsInBounds(ox, oy, placingSpell.aoeShape, placingSpell.aoeSize, grid);
  }, [grid, placingSpell, placeHover]);

  return {
    grid,
    combatantByRef,
    activeRefId,
    myToken,
    reachable,
    occupied,
    threatened,
    previewCells,
    enemyById,
    W,
    H,
    placing,
    targeting,
    interactive,
    casterTok,
    targetAlly,
    targetRangeFeet,
    myConds,
    myAttackRange,
  };
}
