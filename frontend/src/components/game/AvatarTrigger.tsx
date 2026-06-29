"use client";

import type { PlayerDto, PlayerRuntimeState } from "@/types";
import { Tooltip } from "@/components/ui";
import Portrait from "@/components/Portrait";

/**
 * Side-menu / header avatar. Hover (or focus) shows a quick HP + AC tooltip;
 * clicking opens the full character-sheet dialog. Works for any player.
 */
export default function AvatarTrigger({
  player,
  state,
  active = false,
  onOpen,
  placement = "right",
}: {
  player: PlayerDto;
  state?: PlayerRuntimeState | null;
  active?: boolean;
  onOpen: () => void;
  placement?: "top" | "right" | "bottom" | "left";
}) {
  return (
    <Tooltip
      placement={placement}
      content={
        <span className="block whitespace-nowrap text-xs">
          <span className="block font-display font-semibold text-text">
            {player.characterName}
          </span>
          {state ? (
            <span className="mt-0.5 block tabular text-text-muted">
              <span className="text-gold">HP</span> {state.currentHp}/
              {state.maxHp}
              {"    "}
              <span className="text-gold">AC</span> {state.armorClass}
            </span>
          ) : (
            <span className="mt-0.5 block text-text-muted">No stats yet</span>
          )}
        </span>
      }
    >
      <button
        type="button"
        onClick={onOpen}
        aria-label={`${player.characterName} character sheet`}
        className="cursor-pointer rounded-full transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
      >
        <Portrait
          src={player.imageUrl}
          name={player.characterName}
          size="sm"
          active={active}
        />
      </button>
    </Tooltip>
  );
}
