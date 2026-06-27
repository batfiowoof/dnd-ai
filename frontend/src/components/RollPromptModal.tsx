"use client";

import { Modal, Button } from "@/components/ui";
import type { RollMode, RollRequestEvent } from "@/types";

interface RollPromptModalProps {
  request: RollRequestEvent | null;
  connected: boolean;
  onRoll: (mode: RollMode) => void;
}

/**
 * Shown when the DM requests an ability check from the local player. The player chooses
 * normal / advantage / disadvantage; the backend rolls authoritatively and narrates the
 * outcome. Not dismissible — the check must be rolled to continue.
 */
export default function RollPromptModal({
  request,
  connected,
  onRoll,
}: RollPromptModalProps) {
  if (!request) return null;

  const sign =
    request.suggestedModifier >= 0
      ? `+${request.suggestedModifier}`
      : `${request.suggestedModifier}`;
  const heading = request.skill
    ? `${request.ability} (${request.skill})`
    : `${request.ability} check`;

  return (
    <Modal
      open={!!request}
      onClose={() => {}}
      dismissible={false}
      hideClose
      size="sm"
      title="The DM calls for a roll"
    >
      <div className="space-y-4">
        <div className="flex items-center justify-between rounded-lg border border-border-accent bg-bg-elevated px-4 py-3">
          <div>
            <p
              className="text-lg font-bold text-text"
              style={{ fontFamily: "var(--font-display)" }}
            >
              {heading}
            </p>
            <p className="tabular text-xs text-text-muted">
              Your modifier <span className="text-gold">{sign}</span>
            </p>
          </div>
          <div className="flex flex-col items-center rounded-lg border border-gold/40 bg-gold/10 px-3 py-1.5">
            <span className="text-[0.6rem] uppercase tracking-wider text-text-muted">
              DC
            </span>
            <span className="tabular text-xl font-bold text-gold">
              {request.dc}
            </span>
          </div>
        </div>

        {request.reason && (
          <p className="text-sm leading-relaxed text-text-muted">
            {request.reason}
          </p>
        )}

        <div className="space-y-2">
          <Button
            fullWidth
            disabled={!connected}
            onClick={() => onRoll("NORMAL")}
          >
            Roll (d20 {sign})
          </Button>
          <div className="grid grid-cols-2 gap-2">
            <Button
              variant="outline"
              disabled={!connected}
              onClick={() => onRoll("ADVANTAGE")}
            >
              Advantage
            </Button>
            <Button
              variant="outline"
              disabled={!connected}
              onClick={() => onRoll("DISADVANTAGE")}
            >
              Disadvantage
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
