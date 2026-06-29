import type { CombatStateDto, PlayerRuntimeState, SpellSummary } from "@/types";

/** An AoE spell awaiting placement on the board (origin captured by a board click). */
export interface PlacingSpell {
  name: string;
  level: number;
  aoeShape: string;
  aoeSize: number;
}

export interface BattleMapProps {
  combat: CombatStateDto;
  myPlayerId: string;
  isMyTurn: boolean;
  /** Walk speed (ft) of the local player — preview only; from the sheet or 30. */
  mySpeed: number;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  onMove: (x: number, y: number) => void;
  onAttackEnemy: (enemyId: string) => void;
  connected: boolean;
  /** True for the session host — gates the battle-map background upload control. */
  isHost: boolean;
  /** Set while an AoE spell is being placed; drives the template-preview overlay. */
  placingSpell: PlacingSpell | null;
  /** Commit an AoE cast at the clicked origin cell. */
  onCastAoe: (spellName: string, spellLevel: number, x: number, y: number) => void;
  /** Abandon AoE placement mode. */
  onCancelAoe: () => void;
  /** Single/multi-target spell awaiting target selection (click tokens), or null. */
  castingSpell: SpellSummary | null;
  /** Targets picked so far (multi-target spells). */
  pickedTargets: string[];
  /** Select/toggle a target token. */
  onSelectTarget: (refId: string) => void;
  /** Upload a battle-map background (host only). Rejects with a message on 400/403/409. */
  onUploadMap: (file: File) => Promise<void>;
}
