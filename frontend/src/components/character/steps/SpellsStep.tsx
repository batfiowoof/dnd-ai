"use client";

import { useClassSpells } from "@/hooks/useDnd5eData";
import { DataGate } from "@/components/ui";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import { isCasterClass, spellCapsFor } from "@/lib/characterCreation";
import { StepHeading, Guide, SpellPicker } from "@/components/character/shared";

export default function SpellsStep() {
  const selectedClass = useCharacterDraftStore((s) => s.selectedClass);
  const selectedCantrips = useCharacterDraftStore((s) => s.selectedCantrips);
  const selectedSpells = useCharacterDraftStore((s) => s.selectedSpells);
  const toggleCantrip = useCharacterDraftStore((s) => s.toggleCantrip);
  const toggleSpell = useCharacterDraftStore((s) => s.toggleSpell);

  const caster = isCasterClass(selectedClass);
  const spellCaps = spellCapsFor(selectedClass);
  const spellsQuery = useClassSpells(selectedClass?.index, caster);

  const cantripChoices = spellsQuery.data?.cantrips ?? [];
  const levelOneChoices = spellsQuery.data?.level1 ?? [];

  if (!spellCaps) return null;

  return (
    <div>
      <StepHeading>Choose Your Spells</StepHeading>
      <Guide>
        As a {selectedClass?.name}, you begin knowing {spellCaps.cantripsKnown}{" "}
        cantrips and {spellCaps.spellsKnown} level-1 spells. Cantrips are cast at
        will; leveled spells use spell slots.
      </Guide>

      <DataGate
        query={spellsQuery}
        loadingLabel="Loading spell list…"
        onRetry={() => spellsQuery.refetch()}
      >
        <SpellPicker
          title="Cantrips"
          picked={selectedCantrips.length}
          max={spellCaps.cantripsKnown}
          choices={cantripChoices}
          selectedNames={selectedCantrips}
          onToggle={toggleCantrip}
        />
        <div className="mt-5">
          <SpellPicker
            title="Level 1 Spells"
            picked={selectedSpells.length}
            max={spellCaps.spellsKnown}
            choices={levelOneChoices}
            selectedNames={selectedSpells}
            onToggle={toggleSpell}
          />
        </div>
      </DataGate>
    </div>
  );
}
