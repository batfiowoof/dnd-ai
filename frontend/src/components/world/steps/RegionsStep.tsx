"use client";

import type { WorldRegion, WorldSubregion, WorldGenerateRequest } from "@/types";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateRegions, useGenerateSubregions } from "@/hooks/useWorldQueries";
import { draftToContext, emptyRegion, emptySubregion } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";

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
        aiSlot={<AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />}
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
            <RegionSubregions
              region={region}
              onChange={(subregions) => update({ subregions })}
              context={() => draftToContext(draft)}
            />
          </div>
        )}
      />
    </div>
  );
}

interface RegionSubregionsProps {
  region: WorldRegion;
  onChange: (subregions: WorldSubregion[]) => void;
  /** Grounding context for AI generation (draft + authored regions). */
  context: () => WorldGenerateRequest;
}

/** Nested editor for a single region's subregions, with a per-region "Suggest subregions" button. */
function RegionSubregions({ region, onChange, context }: RegionSubregionsProps) {
  const toast = useToast();
  const generate = useGenerateSubregions();
  const subregions = region.subregions ?? [];

  async function handleGenerate() {
    if (!region.name.trim()) {
      toast.error("Name the region before suggesting places within it.");
      return;
    }
    try {
      const items = await generate.mutateAsync({ region: region.name, body: context() });
      onChange([...subregions, ...items]);
      toast.success(`Added ${items.length} subregions`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate subregions"));
    }
  }

  return (
    <div className="rounded-lg border border-border/70 bg-bg-elevated/40 p-3">
      <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
        Subregions <span className="font-normal normal-case">— places within {region.name || "this region"}</span>
      </p>
      <RepeatableSection
        noun="subregions"
        items={subregions}
        onChange={onChange}
        makeEmpty={emptySubregion}
        aiSlot={<AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />}
        titleOf={(s) => s.name}
        renderItem={(sub, updateSub) => (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <LabeledInput
                label="Name"
                placeholder="e.g. The Docks"
                value={sub.name}
                onChange={(v) => updateSub({ name: v })}
              />
              <LabeledInput
                label="Type"
                placeholder="e.g. District, Landmark"
                value={sub.type}
                onChange={(v) => updateSub({ type: v })}
              />
            </div>
            <LabeledTextarea
              label="Description"
              placeholder="What's here within the region."
              value={sub.description}
              onChange={(v) => updateSub({ description: v })}
            />
          </div>
        )}
      />
    </div>
  );
}
