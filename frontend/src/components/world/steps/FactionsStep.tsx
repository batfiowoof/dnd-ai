"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateFactions } from "@/hooks/useWorldQueries";
import { draftToContext } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";
import { emptyFaction } from "@/lib/worldBuilder";

/** Step 3 — 3–5 factions, each a "living magnet" with a goal, a resource, and a pressure to act. */
export default function FactionsStep() {
  const draft = useWorldDraftStore();
  const { factions, setField } = draft;
  const toast = useToast();
  const generate = useGenerateFactions();

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ factions: [...factions, ...items] });
      toast.success(`Added ${items.length} factions`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate factions"));
    }
  }

  return (
    <div>
      <SectionIntro title="Factions">
        Three to five factions work best. Give each a <strong className="text-text">goal</strong>, a{" "}
        <strong className="text-text">resource</strong> it wields, and a{" "}
        <strong className="text-text">pressure</strong> forcing it to move now — that&rsquo;s what
        makes a faction pull the party toward the next development.
      </SectionIntro>

      <RepeatableSection
        noun="factions"
        items={factions}
        onChange={(factions) => setField({ factions })}
        makeEmpty={emptyFaction}
        aiSlot={
          <AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />
        }
        titleOf={(f) => f.name}
        renderItem={(faction, update) => (
          <div className="space-y-3">
            <LabeledInput
              label="Name"
              placeholder="e.g. The Tidewardens"
              value={faction.name}
              onChange={(v) => update({ name: v })}
            />
            <div className="grid gap-3 sm:grid-cols-3">
              <LabeledInput
                label="Goal"
                placeholder="What it wants"
                value={faction.goal}
                onChange={(v) => update({ goal: v })}
              />
              <LabeledInput
                label="Resource"
                placeholder="What gives it power"
                value={faction.resource}
                onChange={(v) => update({ resource: v })}
              />
              <LabeledInput
                label="Pressure"
                placeholder="Why it acts now"
                value={faction.pressure}
                onChange={(v) => update({ pressure: v })}
              />
            </div>
            <LabeledTextarea
              label="Description"
              placeholder="Extra flavour, leaders, methods..."
              value={faction.description}
              onChange={(v) => update({ description: v })}
            />
          </div>
        )}
      />
    </div>
  );
}
