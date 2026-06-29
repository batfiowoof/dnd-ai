"use client";

import { useClasses } from "@/hooks/useDnd5eData";
import { classSkillOptions } from "@/lib/dnd5e";
import { isCaster } from "@/lib/spells";
import { DataGate } from "@/components/ui";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import {
  StepHeading,
  Guide,
  OptionCard,
  ChoicePicker,
} from "@/components/character/shared";

export default function ClassStep() {
  const classesQuery = useClasses();
  const selectedClass = useCharacterDraftStore((s) => s.selectedClass);
  const classSkills = useCharacterDraftStore((s) => s.classSkills);
  const setSelectedClass = useCharacterDraftStore((s) => s.setSelectedClass);
  const toggleClassSkill = useCharacterDraftStore((s) => s.toggleClassSkill);

  return (
    <div>
      <StepHeading>Choose Your Class</StepHeading>
      <Guide>
        Your class is your primary adventuring role. It sets your hit points,
        weapon &amp; armor training, saving throws, and the skills you train. Pick
        a class, then choose your trained skills.
      </Guide>

      <DataGate
        query={classesQuery}
        loadingLabel="Loading classes…"
        onRetry={() => classesQuery.refetch()}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          {classesQuery.data?.map((cls) => {
            const selected = selectedClass?.index === cls.index;
            return (
              <OptionCard
                key={cls.index}
                selected={selected}
                onClick={() => setSelectedClass(cls)}
                title={cls.name}
                badge={`d${cls.hitDie} HD`}
              >
                <div className="text-xs text-text-muted">
                  Primary:{" "}
                  <span className="text-text">{cls.primaryAbility}</span>
                </div>
                <div className="text-xs text-text-muted">
                  Saves: {cls.savingThrows.join(", ")}
                </div>
                {isCaster(cls.name) && (
                  <span className="mt-1 inline-block rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent-light">
                    Spellcaster
                  </span>
                )}
              </OptionCard>
            );
          })}
        </div>

        {selectedClass && (
          <div className="mt-5 rounded-lg border border-border bg-bg-elevated p-4">
            <ChoicePicker
              title={`Trained Skills — choose ${selectedClass.skillProficiencies.choose}`}
              picked={classSkills.length}
              max={selectedClass.skillProficiencies.choose}
              options={classSkillOptions(selectedClass)}
              selected={classSkills}
              onToggle={toggleClassSkill}
            />
          </div>
        )}
      </DataGate>
    </div>
  );
}
