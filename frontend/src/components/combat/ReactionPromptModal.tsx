"use client";

import { useEffect, useRef, useState } from "react";
import Modal from "@/components/ui/Modal";
import Button from "@/components/ui/Button";
import { useSessionStore } from "@/store/sessionStore";

type Choice = "SHIELD" | "ABSORB" | "DECLINE";

interface ReactionPromptModalProps {
  /** Send the player's answer; the paused enemy turn resumes server-side. */
  onReact: (choice: Choice, promptId: string) => void;
}

const SPELL_LABEL: Record<"SHIELD" | "ABSORB", { name: string; blurb: string }> = {
  SHIELD: { name: "Cast Shield", blurb: "+5 AC — may turn the hit into a miss" },
  ABSORB: { name: "Cast Absorb Elements", blurb: "Resist the triggering damage" },
};

function titleCase(s: string | null): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : "";
}

/**
 * The interrupt modal shown ONLY to the player an enemy just hit, when they could spend their
 * reaction on a spell. A locked modal with a ticking window that auto-declines at 0 so the paused
 * enemy turn always resumes. Mirrors the CombatActionModal pending-gate shell + a GameInputBar-style
 * countdown; reuses the Modal/Button primitives.
 */
export default function ReactionPromptModal({ onReact }: ReactionPromptModalProps) {
  const pending = useSessionStore((s) => s.pendingReaction);
  const clearReaction = useSessionStore((s) => s.clearReaction);

  const [seconds, setSeconds] = useState(0);
  const answered = useRef(false);
  const promptId = pending?.promptId ?? null;

  // Answer once, close locally (optimistic), and let the server resume the turn.
  const answer = (choice: Choice) => {
    if (answered.current || !promptId) return;
    answered.current = true;
    onReact(choice, promptId);
    clearReaction();
  };

  // Seed + tick the decision window for THIS prompt; auto-decline at 0.
  useEffect(() => {
    if (!pending) return;
    answered.current = false;
    setSeconds(pending.secondsLeft);
    const id = window.setInterval(() => {
      setSeconds((s) => {
        if (s <= 1) {
          window.clearInterval(id);
          answer("DECLINE");
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => window.clearInterval(id);
    // Re-arm whenever a new prompt arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [promptId]);

  if (!pending) return null;

  const fraction = pending.secondsLeft > 0 ? seconds / pending.secondsLeft : 0;
  const dmg = titleCase(pending.damageType);

  return (
    <Modal
      open
      onClose={() => {}}
      dismissible={false}
      hideClose
      size="sm"
      title="⚡ Reaction!"
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text">
          <span className="font-semibold text-accent">{pending.attacker}</span> lands a
          blow{dmg ? (
            <>
              {" "}for{" "}
              <span className="rounded bg-accent/15 px-1.5 py-0.5 font-mono text-xs font-semibold text-accent">
                {dmg}
              </span>{" "}
              damage
            </>
          ) : null}
          . Spend your reaction?
        </p>

        {/* Ticking window — depletes via transform (no layout shift); tabular seconds. */}
        <div className="flex items-center gap-2">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-border">
            <div
              className="h-full origin-left rounded-full bg-accent transition-transform duration-1000 ease-linear motion-reduce:transition-none"
              style={{ transform: `scaleX(${fraction})` }}
            />
          </div>
          <span className="w-6 text-right font-mono text-xs tabular-nums text-text-muted">
            {seconds}s
          </span>
        </div>

        <div className="flex flex-col gap-2">
          {pending.spellOptions.map((opt) => (
            <Button
              key={opt}
              variant="primary"
              size="md"
              className="flex-col items-start gap-0.5 text-left"
              onClick={() => answer(opt)}
            >
              <span>{SPELL_LABEL[opt].name}</span>
              <span className="text-[11px] font-normal opacity-80">
                {SPELL_LABEL[opt].blurb}
              </span>
            </Button>
          ))}
          <Button variant="ghost" size="sm" onClick={() => answer("DECLINE")}>
            Take the hit
          </Button>
        </div>
      </div>
    </Modal>
  );
}
