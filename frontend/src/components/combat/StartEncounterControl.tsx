"use client";

import { useMemo, useState } from "react";
import { Button, Modal } from "@/components/ui";
import { useCombatMonsters } from "@/hooks/useCombatReference";
import type { MonsterSummary } from "@/types";

interface StartEncounterControlProps {
  connected: boolean;
  onStart: (enemyKeys: string[]) => void;
}

/** Total combatants a single encounter may spawn (matches the backend MAX_ENCOUNTER). */
const MAX_TOTAL = 8;

function crLabel(cr: number | null): string {
  if (cr == null) return "—";
  if (cr === 0.125) return "1/8";
  if (cr === 0.25) return "1/4";
  if (cr === 0.5) return "1/2";
  return String(cr);
}

/**
 * Host-only control to spin up an encounter from the full SRD bestiary. Search by name,
 * pick counts per monster, and the backend rolls initiative and resolves the fight.
 */
export default function StartEncounterControl({
  connected,
  onStart,
}: StartEncounterControlProps) {
  const [open, setOpen] = useState(false);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [query, setQuery] = useState("");

  const monstersQuery = useCombatMonsters(open);
  const monsters = useMemo(() => monstersQuery.data ?? [], [monstersQuery.data]);
  const byKey = useMemo(
    () => new Map(monsters.map((m) => [m.key, m])),
    [monsters]
  );

  const total = Object.values(counts).reduce((a, b) => a + b, 0);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = q
      ? monsters.filter((m) => m.name.toLowerCase().includes(q))
      : monsters;
    return list.slice(0, 60);
  }, [monsters, query]);

  // Chosen monsters surface at the top so they stay visible while searching.
  const chosen = useMemo(
    () =>
      Object.entries(counts)
        .filter(([, n]) => n > 0)
        .map(([key]) => byKey.get(key))
        .filter((m): m is MonsterSummary => !!m),
    [counts, byKey]
  );

  function adjust(key: string, delta: number) {
    setCounts((c) => {
      const cur = c[key] ?? 0;
      // Don't let the running total exceed the encounter cap.
      if (delta > 0 && total >= MAX_TOTAL) return c;
      const next = Math.max(0, Math.min(MAX_TOTAL, cur + delta));
      return { ...c, [key]: next };
    });
  }

  function start() {
    const keys: string[] = [];
    for (const [key, n] of Object.entries(counts)) {
      for (let i = 0; i < n; i++) keys.push(key);
    }
    if (keys.length === 0) return;
    onStart(keys.slice(0, MAX_TOTAL));
    setCounts({});
    setQuery("");
    setOpen(false);
  }

  function row(m: MonsterSummary) {
    return (
      <div
        key={m.key}
        className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2"
      >
        <span className="flex min-w-0 flex-col">
          <span className="truncate text-sm text-text">{m.name}</span>
          <span className="text-[10px] text-text-muted">
            CR {crLabel(m.cr)}
            {m.hp != null ? ` · ${m.hp} HP` : ""}
            {m.ac != null ? ` · AC ${m.ac}` : ""}
          </span>
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => adjust(m.key, -1)}
            className="h-6 w-6 rounded bg-surface-light text-sm font-bold text-text-muted hover:text-accent"
          >
            −
          </button>
          <span className="w-5 text-center tabular text-sm">{counts[m.key] ?? 0}</span>
          <button
            type="button"
            disabled={total >= MAX_TOTAL}
            onClick={() => adjust(m.key, 1)}
            className="h-6 w-6 rounded bg-surface-light text-sm font-bold text-text-muted hover:text-accent disabled:opacity-40"
          >
            +
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      <Button variant="outline" size="sm" disabled={!connected} onClick={() => setOpen(true)}>
        ⚔ Start Encounter
      </Button>

      <Modal open={open} onClose={() => setOpen(false)} title="Start Encounter" size="sm">
        <div className="space-y-2">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search monsters…"
            className="w-full rounded-lg border border-border bg-bg-elevated px-3 py-2 text-sm text-text outline-none placeholder:text-text-muted focus:border-accent/60"
          />

          {monstersQuery.isLoading && (
            <p className="py-2 text-center text-xs text-text-muted">Loading bestiary…</p>
          )}
          {monstersQuery.isError && (
            <p className="py-2 text-center text-xs text-danger">
              Couldn&apos;t load monsters.
            </p>
          )}

          {chosen.length > 0 && (
            <div className="space-y-2 border-b border-border pb-2">{chosen.map(row)}</div>
          )}

          <div className="max-h-72 space-y-2 overflow-y-auto">
            {filtered
              .filter((m) => (counts[m.key] ?? 0) === 0)
              .map(row)}
          </div>

          <p className="text-right text-[10px] text-text-muted">
            {total}/{MAX_TOTAL} combatants
          </p>
          <Button fullWidth disabled={total === 0} onClick={start}>
            {total === 0 ? "Pick enemies" : `Begin (${total})`}
          </Button>
        </div>
      </Modal>
    </>
  );
}
