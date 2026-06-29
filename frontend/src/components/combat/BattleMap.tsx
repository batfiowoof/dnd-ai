"use client";

import { useEffect, useState } from "react";
import { Panel, cn } from "@/components/ui";
import CombatRollFeed from "@/components/combat/CombatRollFeed";
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion";
import { useBattleMapGeometry } from "./battlemap/useBattleMapGeometry";
import type { BattleMapProps } from "./battlemap/types";
import MapToolbar from "./battlemap/MapToolbar";
import AoeBanner from "./battlemap/AoeBanner";
import SvgDefs from "./battlemap/SvgDefs";
import BackgroundLayer from "./battlemap/BackgroundLayer";
import GridLayer from "./battlemap/GridLayer";
import TerrainLayer from "./battlemap/TerrainLayer";
import FeatureLayer from "./battlemap/FeatureLayer";
import ThreatLayer from "./battlemap/ThreatLayer";
import MoveOverlay from "./battlemap/MoveOverlay";
import { AoePreviewLayer, AoeCaptureLayer } from "./battlemap/AoeOverlay";
import TokenLayer from "./battlemap/TokenLayer";
import ConditionBadges from "./battlemap/ConditionBadges";
import DeathSaveOverlay from "./battlemap/DeathSaveOverlay";
import TerrainLegend from "./battlemap/TerrainLegend";

/** Re-exported so existing importers (e.g. lobby page) keep using `@/components/combat/BattleMap`. */
export type { PlacingSpell } from "./battlemap/types";

/**
 * Tactical battle grid. Renders a responsive, layered SVG board: optional
 * background image + scrim, grid lines, terrain (pattern/icon — never colour
 * alone), feature markers, the active player's reachable-move highlight, and
 * combatant tokens (Portrait + HP ring, gold ring for the active combatant,
 * distinguishing glyph for enemies). On your turn, click a reachable empty cell
 * to move or an enemy token to attack. Server stays authoritative — the
 * reachable highlight is a client mirror of the server's movement rules.
 *
 * Layer order is kept explicit (z-order matters); each `<*Layer>` child is an
 * extracted SVG layer fed by {@link useBattleMapGeometry}.
 */
export default function BattleMap({
  combat,
  myPlayerId,
  isMyTurn,
  mySpeed,
  runtimeByPlayerId,
  onMove,
  onAttackEnemy,
  connected,
  isHost,
  placingSpell,
  onCastAoe,
  onCancelAoe,
  castingSpell,
  pickedTargets,
  onSelectTarget,
  onUploadMap,
}: BattleMapProps) {
  const reduced = usePrefersReducedMotion();
  /** Hovered origin cell while placing an AoE template ("x,y"), or null. Owned here so the
   *  preview (below the tokens) and the capture grid (on top) can share it. */
  const [placeHover, setPlaceHover] = useState<string | null>(null);

  // Leaving placement mode clears any lingering template hover.
  useEffect(() => {
    if (!placingSpell) setPlaceHover(null);
  }, [placingSpell]);

  const geo = useBattleMapGeometry({
    combat,
    myPlayerId,
    isMyTurn,
    mySpeed,
    runtimeByPlayerId,
    connected,
    placingSpell,
    castingSpell,
    placeHover,
  });

  // Resume from a pre-grid encounter → nothing to draw.
  const { grid } = geo;
  if (!grid) return null;

  const {
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
  } = geo;

  return (
    <Panel className={cn("p-2 sm:p-3", !reduced && "animate-rise")}>
      <MapToolbar
        isMyTurn={isMyTurn}
        myToken={myToken}
        mySpeed={mySpeed}
        isHost={isHost}
        onUploadMap={onUploadMap}
      />

      <AoeBanner placingSpell={placingSpell} onCancelAoe={onCancelAoe} />

      <div
        className="relative mx-auto w-full"
        style={{ maxWidth: Math.min(W, 960) }}
      >
        {/* Compact roll feed docked on the map (enemy/NPC/other rolls). */}
        <CombatRollFeed />
        <svg
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="xMidYMid meet"
          className="block h-auto w-full select-none rounded-lg"
          role="group"
          aria-label="Tactical battle grid"
        >
          <SvgDefs />

          {/* 1/2/2b — Background image + scrim, board fill, vignette */}
          <BackgroundLayer grid={grid} W={W} H={H} />

          {/* 3 — Grid lines */}
          <GridLayer grid={grid} W={W} H={H} />

          {/* 4 — Terrain (pattern + icon, never colour alone) */}
          <TerrainLayer grid={grid} />

          {/* 5 — Feature markers (gold diamond + label) */}
          <FeatureLayer grid={grid} />

          {/* 5.5 — Opportunity-attack threat zones (enemy melee reach) on the player's turn */}
          <ThreatLayer show={isMyTurn && !placing} threatened={threatened} />

          {/* 6 — Reachable-move highlight (active player's turn only; hidden while targeting) */}
          <MoveOverlay
            show={interactive && !targeting}
            reachable={reachable}
            occupied={occupied}
            myToken={myToken}
            reduced={reduced}
            onMove={onMove}
          />

          {/* 7 — AoE template preview (placement mode) */}
          <AoePreviewLayer
            show={placing}
            previewCells={previewCells}
            placeHover={placeHover}
          />

          {/* 8 — Tokens */}
          <TokenLayer
            grid={grid}
            combatantByRef={combatantByRef}
            activeRefId={activeRefId}
            enemyById={enemyById}
            runtimeByPlayerId={runtimeByPlayerId}
            casterTok={casterTok}
            myAttackRange={myAttackRange}
            targeting={targeting}
            interactive={interactive}
            targetAlly={targetAlly}
            targetRangeFeet={targetRangeFeet}
            myConds={myConds}
            pickedTargets={pickedTargets}
            reduced={reduced}
            myPlayerId={myPlayerId}
            mySpeed={mySpeed}
            onAttackEnemy={onAttackEnemy}
            onSelectTarget={onSelectTarget}
          />

          {/* 8.5 — Condition badges */}
          <ConditionBadges
            grid={grid}
            combatantByRef={combatantByRef}
            enemyById={enemyById}
            runtimeByPlayerId={runtimeByPlayerId}
            reduced={reduced}
          />

          {/* 9 — Death-save overlay (above the faded downed tokens) */}
          <DeathSaveOverlay
            grid={grid}
            combatantByRef={combatantByRef}
            runtimeByPlayerId={runtimeByPlayerId}
          />

          {/* 10 — AoE placement capture layer (transparent cells on top) */}
          <AoeCaptureLayer
            show={placing}
            grid={grid}
            placingSpell={placingSpell!}
            connected={connected}
            onCastAoe={onCastAoe}
            setPlaceHover={setPlaceHover}
          />
        </svg>
      </div>

      {/* Terrain legend */}
      <TerrainLegend />
    </Panel>
  );
}
