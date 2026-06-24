"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, cn } from "@/components/ui";
import { useSessionStore, type DiceRoll } from "@/store/sessionStore";
import Die from "./Die";

const ROLL_MS = 850; // tumble duration before the real faces are revealed
const AUTOCLOSE_MS = 2600; // how long the result lingers before auto-dismiss
const MAX_DICE = 12; // cap rendered dice for sane layout

function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  );
}

function randomFace(sides: number): number {
  return Math.floor(Math.random() * sides) + 1;
}

/**
 * Listens for the store's authoritative `lastRoll` and animates it: the dice
 * tumble showing rapidly-cycling random faces, then settle on the backend's
 * real values. The displayed total is always the backend total — correct and
 * identical on every client. Honors reduced-motion (skips the tumble).
 */
export default function DiceRollModal() {
  const lastRoll = useSessionStore((s) => s.lastRoll);

  const [active, setActive] = useState<DiceRoll | null>(null);
  const [rolling, setRolling] = useState(false);
  const [faces, setFaces] = useState<number[]>([]);
  const seenId = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => {
    if (!lastRoll || lastRoll.id === seenId.current) return;
    seenId.current = lastRoll.id;

    // Clear any in-flight animation from a previous roll.
    timers.current.forEach(clearTimeout);
    timers.current = [];

    const shown = lastRoll.faces.slice(0, MAX_DICE);
    setActive(lastRoll);

    if (prefersReducedMotion()) {
      setRolling(false);
      setFaces(shown);
      timers.current.push(
        setTimeout(() => setActive(null), AUTOCLOSE_MS)
      );
      return;
    }

    setRolling(true);
    setFaces(shown.map(() => randomFace(lastRoll.sides)));

    const cycle = setInterval(() => {
      setFaces(shown.map(() => randomFace(lastRoll.sides)));
    }, 80);

    timers.current.push(
      setTimeout(() => {
        clearInterval(cycle);
        setRolling(false);
        setFaces(shown); // settle on the authoritative faces
      }, ROLL_MS)
    );
    timers.current.push(
      setTimeout(() => setActive(null), ROLL_MS + AUTOCLOSE_MS)
    );

    return () => clearInterval(cycle);
  }, [lastRoll]);

  useEffect(() => {
    return () => timers.current.forEach(clearTimeout);
  }, []);

  if (!active) return null;

  const extra = active.faces.length - faces.length;

  return (
    <Modal
      open={!!active}
      onClose={() => setActive(null)}
      title={active.label}
      size="sm"
    >
      <div className="flex flex-col items-center gap-4 text-center">
        <p className="text-xs uppercase tracking-wider text-text-muted">
          {active.playerName} rolls{" "}
          <span className="font-mono text-text">{active.notation}</span>
        </p>

        <div className="flex flex-wrap items-center justify-center gap-2">
          {faces.map((f, i) => (
            <Die
              key={i}
              value={f}
              sides={active.sides}
              rolling={rolling}
              crit={!rolling && active.crit}
              fumble={!rolling && active.fumble}
            />
          ))}
          {extra > 0 && (
            <span className="self-center text-xs text-text-muted">
              +{extra} more
            </span>
          )}
        </div>

        {!rolling && (
          <div className="animate-rise">
            {active.modifier !== 0 && (
              <p className="text-xs text-text-muted">
                dice {active.total - active.modifier}{" "}
                {active.modifier > 0 ? "+" : "−"} {Math.abs(active.modifier)}{" "}
                modifier
              </p>
            )}
            <p
              className={cn(
                "font-display text-4xl font-bold tabular-nums",
                active.crit
                  ? "text-gold"
                  : active.fumble
                    ? "text-danger"
                    : "text-accent"
              )}
            >
              {active.total}
            </p>
            {active.crit && (
              <p className="text-xs font-semibold uppercase tracking-widest text-gold">
                Critical!
              </p>
            )}
            {active.fumble && (
              <p className="text-xs font-semibold uppercase tracking-widest text-danger">
                Fumble!
              </p>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
