"use client";

import { Button, cn } from "@/components/ui";

interface RepeatableSectionProps<T> {
  /** Plural noun for the empty state + Add button (e.g. "regions", "factions"). */
  noun: string;
  items: T[];
  onChange: (items: T[]) => void;
  makeEmpty: () => T;
  /** Collapsed title for a card (usually the item's name). */
  titleOf: (item: T) => string;
  /** Renders the editable form for one item; `update` shallow-merges a patch. */
  renderItem: (item: T, update: (patch: Partial<T>) => void, index: number) => React.ReactNode;
  /** Optional AI affordance rendered next to the Add button (Phase 5). */
  aiSlot?: React.ReactNode;
  /** Optional cap on how many items can be added. */
  max?: number;
}

/**
 * Generic editor for a list of authored entities (regions, factions, NPCs, monsters, milestones):
 * an Add control with an optional AI slot, a helpful empty state, and a removable card per item.
 * The per-item form is supplied by the caller via {@link RepeatableSectionProps.renderItem}.
 */
export default function RepeatableSection<T>({
  noun,
  items,
  onChange,
  makeEmpty,
  titleOf,
  renderItem,
  aiSlot,
  max,
}: RepeatableSectionProps<T>) {
  const atMax = max != null && items.length >= max;

  function add() {
    if (atMax) return;
    onChange([...items, makeEmpty()]);
  }

  function update(index: number, patch: Partial<T>) {
    onChange(items.map((it, i) => (i === index ? { ...it, ...patch } : it)));
  }

  function remove(index: number) {
    onChange(items.filter((_, i) => i !== index));
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={add} disabled={atMax}>
          + Add {singular(noun)}
        </Button>
        {aiSlot}
        <span className="ml-auto text-xs text-text-muted">
          {items.length} {items.length === 1 ? singular(noun) : noun}
        </span>
      </div>

      {items.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border bg-bg-elevated/50 p-6 text-center text-sm text-text-muted">
          No {noun} yet. Add one manually{aiSlot ? ", or generate with AI" : ""}.
        </div>
      ) : (
        <div className="space-y-3">
          {items.map((item, i) => (
            <div
              key={i}
              className={cn(
                "rounded-xl border border-border bg-surface p-4",
                "transition hover:border-accent/40"
              )}
            >
              <div className="mb-3 flex items-center justify-between gap-2">
                <span
                  className="truncate text-sm font-semibold text-gold"
                  style={{ fontFamily: "var(--font-display)" }}
                >
                  {titleOf(item) || `${capitalize(singular(noun))} ${i + 1}`}
                </span>
                <button
                  type="button"
                  onClick={() => remove(i)}
                  aria-label={`Remove ${singular(noun)} ${i + 1}`}
                  className="flex h-8 w-8 flex-shrink-0 cursor-pointer items-center justify-center rounded-md text-text-muted transition hover:bg-accent/10 hover:text-accent"
                >
                  <svg
                    className="h-4 w-4"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={1.8}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m2 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                  </svg>
                </button>
              </div>
              {renderItem(item, (patch) => update(i, patch), i)}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** Naive singularizer good enough for our nouns (regions → region, npcs → npc). */
function singular(noun: string): string {
  if (noun.endsWith("ies")) return noun.slice(0, -3) + "y";
  if (noun.endsWith("s")) return noun.slice(0, -1);
  return noun;
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
