"use client";

import { useState } from "react";
import { Button, Modal, cn } from "@/components/ui";

interface StartEncounterControlProps {
  connected: boolean;
  onStart: (enemyKeys: string[]) => void;
}

const BESTIARY: { key: string; name: string }[] = [
  { key: "GOBLIN", name: "Goblin" },
  { key: "WOLF", name: "Wolf" },
  { key: "BANDIT", name: "Bandit" },
  { key: "SKELETON", name: "Skeleton" },
  { key: "ORC", name: "Orc" },
  { key: "GIANT_RAT", name: "Giant Rat" },
];

/**
 * Host-only control to spin up an encounter. Pick counts per bestiary entry and
 * the backend rolls initiative and resolves the fight.
 */
export default function StartEncounterControl({
  connected,
  onStart,
}: StartEncounterControlProps) {
  const [open, setOpen] = useState(false);
  const [counts, setCounts] = useState<Record<string, number>>({});

  const total = Object.values(counts).reduce((a, b) => a + b, 0);

  function adjust(key: string, delta: number) {
    setCounts((c) => {
      const next = Math.max(0, Math.min(8, (c[key] ?? 0) + delta));
      return { ...c, [key]: next };
    });
  }

  function start() {
    const keys: string[] = [];
    for (const { key } of BESTIARY) {
      for (let i = 0; i < (counts[key] ?? 0); i++) keys.push(key);
    }
    if (keys.length === 0) return;
    onStart(keys);
    setCounts({});
    setOpen(false);
  }

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        disabled={!connected}
        onClick={() => setOpen(true)}
      >
        ⚔ Start Encounter
      </Button>

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Start Encounter"
        size="sm"
      >
        <div className="space-y-2">
          {BESTIARY.map(({ key, name }) => (
            <div
              key={key}
              className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2"
            >
              <span className="text-sm text-text">{name}</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => adjust(key, -1)}
                  className="h-6 w-6 rounded bg-surface-light text-sm font-bold text-text-muted hover:text-accent"
                >
                  −
                </button>
                <span className="w-5 text-center tabular text-sm">
                  {counts[key] ?? 0}
                </span>
                <button
                  type="button"
                  onClick={() => adjust(key, 1)}
                  className="h-6 w-6 rounded bg-surface-light text-sm font-bold text-text-muted hover:text-accent"
                >
                  +
                </button>
              </div>
            </div>
          ))}
          <Button
            fullWidth
            disabled={total === 0}
            onClick={start}
            className={cn("mt-2")}
          >
            {total === 0 ? "Pick enemies" : `Begin (${total})`}
          </Button>
        </div>
      </Modal>
    </>
  );
}
