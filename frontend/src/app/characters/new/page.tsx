"use client";

import { useMemo, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useCreateCharacter } from "@/hooks/useCharacterQueries";
import { useEquipmentKindMap } from "@/hooks/useDnd5eData";
import { equipmentToStrings } from "@/lib/dnd5e";
import { Button, cn, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import {
  isCasterClass,
  wizardSteps,
  finalAbilities,
  derivedHP,
  derivedAC,
  derivedSpeed,
  resolvedItems,
  canProceed,
} from "@/lib/characterCreation";
import {
  ClassStep,
  BackgroundStep,
  SpeciesStep,
  AbilitiesStep,
  SpellsStep,
  EquipmentStep,
  DetailsStep,
  ReviewStep,
} from "@/components/character/steps";

export default function CharacterCreatePage() {
  return (
    <RequireAuth>
      <CharacterCreateForm />
    </RequireAuth>
  );
}

function CharacterCreateForm() {
  const router = useRouter();
  const { username } = useAuth();
  const toast = useToast();
  const createMutation = useCreateCharacter();
  const saving = createMutation.isPending;

  // Equipment kind-map is needed to resolve the final inventory for handleSave.
  const equipKindMapQuery = useEquipmentKindMap();

  // ── Wizard draft (Zustand) ───────────────────────────────────────
  const draft = useCharacterDraftStore();
  const { step, setStep } = draft;

  // Reset the draft on mount (mirrors sessionStore usage in the lobby page).
  useEffect(() => {
    useCharacterDraftStore.getState().reset();
    return () => useCharacterDraftStore.getState().reset();
  }, []);

  // Caster gating lives in the shared layer — it decides whether Spells exists.
  const caster = isCasterClass(draft.selectedClass);
  const steps = useMemo(() => wizardSteps(caster), [caster]);
  const stepIndex = steps.indexOf(step);

  // If the step list changes (class swap toggles Spells) and the current step is
  // gone, fall back to the first step.
  useEffect(() => {
    if (!steps.includes(step)) setStep("Class");
  }, [steps, step, setStep]);

  function nextStep() {
    if (stepIndex < steps.length - 1) setStep(steps[stepIndex + 1]);
  }
  function prevStep() {
    if (stepIndex > 0) setStep(steps[stepIndex - 1]);
  }

  async function handleSave() {
    const { selectedSpecies, selectedClass, selectedBackground } = draft;
    if (!username || !selectedSpecies || !selectedClass || !selectedBackground)
      return;
    if (!draft.name.trim()) return;
    try {
      const proficiencies = Array.from(
        new Set([
          ...selectedBackground.skillProficiencies,
          selectedBackground.toolProficiency,
          ...draft.classSkills,
        ])
      ).filter(Boolean);
      const features = [
        ...selectedSpecies.traits.map((t) => t.name),
        selectedBackground.feat,
      ];
      const final = finalAbilities(draft);
      const items = resolvedItems(draft, equipKindMapQuery.data);

      await createMutation.mutateAsync({
        name: draft.name.trim(),
        race: selectedSpecies.name,
        characterClass: selectedClass.name,
        level: 1,
        background: selectedBackground.name,
        alignment: draft.alignment,
        strength: final.strength,
        dexterity: final.dexterity,
        constitution: final.constitution,
        intelligence: final.intelligence,
        wisdom: final.wisdom,
        charisma: final.charisma,
        hitPoints: derivedHP(draft),
        armorClass: derivedAC(draft),
        speed: derivedSpeed(draft),
        equipment: equipmentToStrings(items),
        proficiencies,
        features,
        cantrips: draft.selectedCantrips,
        knownSpells: draft.selectedSpells,
        startingInventory: items,
        backstory: draft.backstory,
        imageUrl: draft.imageUrl.trim(),
      });
      router.push("/characters");
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to save character"));
    }
  }

  return (
    <main className="min-h-dvh p-4 sm:p-6">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <button
            onClick={() => router.push("/characters")}
            className="cursor-pointer text-sm text-text-muted transition hover:text-accent"
          >
            &larr; Back to Characters
          </button>
          <h1
            className="text-2xl font-bold text-accent"
            style={{ fontFamily: "var(--font-display)" }}
          >
            Create Character
          </h1>
          <div className="w-24" />
        </div>

        {/* Step indicator */}
        <div className="mb-8 flex flex-wrap items-center justify-center gap-1">
          {steps.map((s, i) => (
            <div key={s} className="flex items-center">
              <button
                onClick={() => i <= stepIndex && setStep(s)}
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
              {i < steps.length - 1 && (
                <div
                  className={cn(
                    "mx-1 h-px w-5 transition",
                    i < stepIndex ? "bg-accent" : "bg-border"
                  )}
                />
              )}
            </div>
          ))}
        </div>

        <div className="rounded-xl border border-border-accent bg-surface p-6 panel-corners">
          {step === "Class" && <ClassStep />}
          {step === "Background" && <BackgroundStep />}
          {step === "Species" && <SpeciesStep />}
          {step === "Abilities" && <AbilitiesStep />}
          {step === "Spells" && <SpellsStep />}
          {step === "Equipment" && <EquipmentStep />}
          {step === "Details" && <DetailsStep />}
          {step === "Review" && <ReviewStep />}

          {/* ── Navigation ────────────────────────────── */}
          <div className="mt-6 flex items-center justify-between">
            <Button onClick={prevStep} disabled={stepIndex === 0} variant="ghost">
              &larr; Back
            </Button>

            {step === "Review" ? (
              <Button onClick={handleSave} loading={saving} size="lg">
                {saving ? "Saving..." : "Create Character"}
              </Button>
            ) : (
              <Button
                onClick={nextStep}
                disabled={!canProceed(step, draft)}
                size="lg"
              >
                Next &rarr;
              </Button>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
