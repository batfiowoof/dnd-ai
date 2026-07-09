"use client";

import { useEffect, useRef, useState } from "react";
import Modal from "@/components/ui/Modal";
import Button from "@/components/ui/Button";
import { useSessionStore } from "@/store/sessionStore";

type Resource = "INSPIRATION" | "LUCK" | "KEEP";

interface RerollPromptModalProps {
  /** Send the player's answer; the paused DM roll resolves server-side. */
  onReroll: (resource: Resource, promptId: string) => void;
}

const RESOURCE_LABEL: Record<
  "INSPIRATION" | "LUCK",
  { name: string; blurb: string }
> = {
  INSPIRATION: {
    name: "Spend Heroic Inspiration",
    blurb: "Reroll — you must use the new roll",
  },
  LUCK: {
    name: "Spend a Luck Point",
    blurb: "Reroll — keep whichever roll is better",
  },
};

/**
 * The interrupt modal shown ONLY to the player whose d20 just failed, when they hold a resource that
 * can reroll it (Heroic Inspiration and/or Lucky points). A locked modal with a ticking window that
 * auto-keeps the original roll at 0 so the paused DM roll always resolves. Mirrors
 * {@link ReactionPromptModal}; reuses the Modal/Button primitives and dark-red theme.
 */
export default function RerollPromptModal({ onReroll }: RerollPromptModalProps) {
  const pending = useSessionStore((s) => s.pendingReroll);
  const clearReroll = useSessionStore((s) => s.clearReroll);

  const [seconds, setSeconds] = useState(0);
  const answered = useRef(false);
  const promptId = pending?.promptId ?? null;

  // Answer once, close locally (optimistic), and let the server resolve the roll.
  const answer = (resource: Resource) => {
    if (answered.current || !promptId) return;
    answered.current = true;
    onReroll(resource, promptId);
    clearReroll();
  };

  // Seed + tick the decision window for THIS prompt; auto-keep at 0.
  useEffect(() => {
    if (!pending) return;
    answered.current = false;
    setSeconds(pending.secondsLeft);
    const id = window.setInterval(() => {
      setSeconds((s) => {
        if (s <= 1) {
          window.clearInterval(id);
          answer("KEEP");
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

  return (
    <Modal
      open
      onClose={() => {}}
      dismissible={false}
      hideClose
      size="sm"
      title="🎲 Reroll?"
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text">
          Your{" "}
          <span className="font-semibold text-accent">{pending.label}</span>{" "}
          came up{" "}
          <span className="rounded bg-accent/15 px-1.5 py-0.5 font-mono text-xs font-semibold text-accent">
            {pending.originalTotal}
          </span>
          , short of the DC{" "}
          <span className="font-mono text-xs font-semibold">{pending.dc}</span>.
          Spend a resource to reroll?
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
          {pending.options.map((opt) => (
            <Button
              key={opt}
              variant="primary"
              size="md"
              className="flex-col items-start gap-0.5 text-left"
              onClick={() => answer(opt)}
            >
              <span>
                {RESOURCE_LABEL[opt].name}
                {opt === "LUCK" ? ` (${pending.luckPoints} left)` : ""}
              </span>
              <span className="text-[11px] font-normal opacity-80">
                {RESOURCE_LABEL[opt].blurb}
              </span>
            </Button>
          ))}
          <Button variant="ghost" size="sm" onClick={() => answer("KEEP")}>
            Keep the roll
          </Button>
        </div>
      </div>
    </Modal>
  );
}
