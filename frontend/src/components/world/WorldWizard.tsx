"use client";

import { useRouter } from "next/navigation";
import { Button, cn, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import {
  WORLD_STEPS,
  canProceed,
  draftToRequest,
  type WorldStep,
} from "@/lib/worldBuilder";
import type { WorldCreateUpdateRequest } from "@/types";
import {
  OverviewStep,
  RegionsStep,
  FactionsStep,
  NpcsStep,
  MonstersStep,
  MilestonesStep,
  ReviewStep,
} from "@/components/world/steps";

interface WorldWizardProps {
  saveLabel: string;
  saving: boolean;
  onSave: (request: WorldCreateUpdateRequest) => Promise<void>;
}

/**
 * The shared World Builder wizard: a clickable step bar, the active step, and Back/Next/Save
 * navigation. Reads and writes the {@link useWorldDraftStore} draft; the create/edit pages own
 * loading + persistence and pass {@link WorldWizardProps.onSave}.
 */
export default function WorldWizard({ saveLabel, saving, onSave }: WorldWizardProps) {
  const router = useRouter();
  const toast = useToast();
  const draft = useWorldDraftStore();
  const { step, setStep } = draft;

  const stepIndex = WORLD_STEPS.indexOf(step);
  const isLast = stepIndex === WORLD_STEPS.length - 1;
  const proceed = canProceed(step, draft);

  function next() {
    if (!proceed) {
      toast.error("Give your world a name to continue.");
      return;
    }
    if (!isLast) setStep(WORLD_STEPS[stepIndex + 1]);
  }

  function prev() {
    if (stepIndex > 0) setStep(WORLD_STEPS[stepIndex - 1]);
  }

  async function handleSave() {
    if (!draft.name.trim()) {
      toast.error("Give your world a name to continue.");
      setStep("Overview");
      return;
    }
    try {
      await onSave(draftToRequest(draft));
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to save world"));
    }
  }

  return (
    <div className="mx-auto max-w-2xl">
      {/* Step indicator */}
      <div className="mb-8 flex flex-wrap items-center justify-center gap-1">
        {WORLD_STEPS.map((s, i) => (
          <div key={s} className="flex items-center">
            <button
              type="button"
              onClick={() => i <= stepIndex && setStep(s as WorldStep)}
              className={cn(
                "rounded-full px-3 py-1 text-xs font-semibold transition",
                s === step
                  ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
                  : i < stepIndex
                    ? "cursor-pointer bg-accent-dark/30 text-accent hover:bg-accent-dark/50"
                    : "bg-surface-light text-text-muted"
              )}
            >
              <span className="tabular">{i + 1}.</span> {s}
            </button>
            {i < WORLD_STEPS.length - 1 && (
              <div
                className={cn(
                  "mx-1 h-px w-4 transition",
                  i < stepIndex ? "bg-accent" : "bg-border"
                )}
              />
            )}
          </div>
        ))}
      </div>

      {/* Active step */}
      <div className="animate-rise">
        {step === "Overview" && <OverviewStep />}
        {step === "Regions" && <RegionsStep />}
        {step === "Factions" && <FactionsStep />}
        {step === "NPCs" && <NpcsStep />}
        {step === "Monsters" && <MonstersStep />}
        {step === "Milestones" && <MilestonesStep />}
        {step === "Review" && <ReviewStep />}
      </div>

      {/* Navigation */}
      <div className="mt-8 flex items-center justify-between gap-2">
        <Button
          variant="ghost"
          onClick={stepIndex === 0 ? () => router.push("/worlds") : prev}
        >
          {stepIndex === 0 ? "Cancel" : "Back"}
        </Button>
        {isLast ? (
          <Button onClick={handleSave} loading={saving}>
            {saveLabel}
          </Button>
        ) : (
          <Button onClick={next} disabled={!proceed}>
            Next
          </Button>
        )}
      </div>
    </div>
  );
}
