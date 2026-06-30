"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateRegions } from "@/hooks/useWorldQueries";
import { draftToContext } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";
import { emptyRegion } from "@/lib/worldBuilder";

/** Step 2 — 5–7 locations that matter (home base, rival stronghold, mystery site, wilds, neutral). */
export default function RegionsStep() {
  const draft = useWorldDraftStore();
  const { regions, setField } = draft;
  const toast = useToast();
  const generate = useGenerateRegions();

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ regions: [...regions, ...items] });
      toast.success(`Added ${items.length} regions`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate regions"));
    }
  }

  return (
    <div>
      <SectionIntro title="Regions">
        Aim for five to seven places that matter — a home base, a rival&rsquo;s stronghold, a mystery
        site, a wild zone, and some neutral ground. You don&rsquo;t need a whole map, just anchors.
      </SectionIntro>

      <RepeatableSection
        noun="regions"
        items={regions}
        onChange={(regions) => setField({ regions })}
        makeEmpty={emptyRegion}
        aiSlot={
          <AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />
        }
        titleOf={(r) => r.name}
        renderItem={(region, update) => (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <LabeledInput
                label="Name"
                placeholder="e.g. Saltmarsh"
                value={region.name}
                onChange={(v) => update({ name: v })}
              />
              <LabeledInput
                label="Type"
                placeholder="e.g. City, Ruin, Wilds"
                value={region.type}
                onChange={(v) => update({ type: v })}
              />
            </div>
            <LabeledTextarea
              label="Description"
              placeholder="What's here, and why adventurers care."
              value={region.description}
              onChange={(v) => update({ description: v })}
            />
          </div>
        )}
      />
    </div>
  );
}
