"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateMilestones } from "@/hooks/useWorldQueries";
import { draftToContext } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";
import { emptyMilestone } from "@/lib/worldBuilder";

/** Step 6 — three-act leveling gates the DM awards to advance the whole party a level. */
export default function MilestonesStep() {
  const draft = useWorldDraftStore();
  const { milestones, setField } = draft;
  const toast = useToast();
  const generate = useGenerateMilestones();

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ milestones: [...milestones, ...items] });
      toast.success(`Added ${items.length} milestones`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate milestones"));
    }
  }

  return (
    <div>
      <SectionIntro title="Milestones">
        Milestones are the party&rsquo;s leveling gates. Think in three acts — a hook, rising
        complications, a finale — with a few beats each. The DM can only award the milestones you
        author here, and each one levels the whole party once.
      </SectionIntro>

      <RepeatableSection
        noun="milestones"
        items={milestones}
        onChange={(milestones) => setField({ milestones })}
        makeEmpty={emptyMilestone}
        aiSlot={
          <AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />
        }
        titleOf={(m) => m.title}
        renderItem={(milestone, update) => (
          <div className="space-y-3">
            <LabeledInput
              label="Title"
              placeholder="e.g. Masters of the Tide"
              value={milestone.title}
              onChange={(v) => update({ title: v })}
            />
            <LabeledTextarea
              label="When it triggers"
              hint="Describe the beat that earns the level-up. The DM watches for this."
              placeholder="e.g. The party secures their own seaworthy vessel."
              value={milestone.description}
              onChange={(v) => update({ description: v })}
            />
          </div>
        )}
      />
    </div>
  );
}
