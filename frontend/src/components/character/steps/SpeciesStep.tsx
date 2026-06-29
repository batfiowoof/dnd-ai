"use client";

import { useSpecies } from "@/hooks/useDnd5eData";
import { DataGate } from "@/components/ui";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import { StepHeading, Guide, OptionCard } from "@/components/character/shared";

export default function SpeciesStep() {
  const speciesQuery = useSpecies();
  const selectedSpecies = useCharacterDraftStore((s) => s.selectedSpecies);
  const setSelectedSpecies = useCharacterDraftStore((s) => s.setSelectedSpecies);

  return (
    <div>
      <StepHeading>Choose Your Species</StepHeading>
      <Guide>
        Your species defines your character&apos;s lineage and grants special
        traits, size, and speed. Under the 2024 rules species no longer changes
        your ability scores — those come from your background.
      </Guide>

      <DataGate
        query={speciesQuery}
        loadingLabel="Loading species…"
        onRetry={() => speciesQuery.refetch()}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {speciesQuery.data?.map((sp) => {
            const selected = selectedSpecies?.index === sp.index;
            return (
              <OptionCard
                key={sp.index}
                selected={selected}
                onClick={() => setSelectedSpecies(sp)}
                title={sp.name}
                badge={`${sp.speed} ft`}
              >
                <div className="text-xs text-text-muted">
                  {[sp.creatureType, sp.size.split("(")[0].trim()]
                    .filter(Boolean)
                    .join(" · ")}
                </div>
                <div className="mt-0.5 text-xs text-text-muted">
                  {sp.traits.length} trait
                  {sp.traits.length === 1 ? "" : "s"}
                </div>
              </OptionCard>
            );
          })}
        </div>

        {selectedSpecies && (
          <div className="mt-5 rounded-lg border border-border bg-bg-elevated p-4">
            <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
              {selectedSpecies.name} Traits
            </h3>
            <div className="space-y-2">
              {selectedSpecies.traits.map((t) => (
                <details
                  key={t.name}
                  className="rounded-md border border-border bg-surface"
                >
                  <summary className="cursor-pointer px-3 py-2 text-sm font-semibold text-text">
                    {t.name}
                  </summary>
                  <p className="border-t border-border px-3 py-2 text-xs leading-relaxed text-text-muted">
                    {t.desc}
                  </p>
                </details>
              ))}
            </div>
          </div>
        )}
      </DataGate>
    </div>
  );
}
