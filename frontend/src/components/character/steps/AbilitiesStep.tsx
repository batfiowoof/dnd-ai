"use client";

import {
  ABILITY_NAMES,
  POINT_BUY_COSTS,
  POINT_BUY_TOTAL,
  STANDARD_ARRAY,
  getAbilityModifier,
  formatModifier,
} from "@/lib/dnd5e";
import { controlClass, cn } from "@/components/ui";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import {
  ABILITY_LABELS,
  ABILITY_DESCRIPTIONS,
  type AbilityMethod,
  bgTargets,
  bgBonuses,
  finalAbilities,
  baseScore,
  pointBuySpent,
  usedStandardValues,
} from "@/lib/characterCreation";
import {
  Segmented,
  AsiPanel,
  StepHeading,
  Guide,
} from "@/components/character/shared";

export default function AbilitiesStep() {
  const draft = useCharacterDraftStore();
  const {
    abilityMethod,
    selectedBackground,
    asi,
    baseAbilities,
    standardAssignments,
    setAbilityMethod,
    setAsi,
    setBaseAbilities,
    setStandardAssignments,
  } = draft;

  const targets = bgTargets(draft);
  const bonuses = bgBonuses(draft);
  const final = finalAbilities(draft);
  const base = baseScore(draft);
  const spent = pointBuySpent(draft);
  const usedStandard = usedStandardValues(draft);

  return (
    <div>
      <StepHeading>Ability Scores</StepHeading>
      <Guide>
        Set a <strong className="text-text">base</strong> for each ability with{" "}
        <strong>Standard Array</strong> (15, 14, 13, 12, 10, 8) or{" "}
        <strong>Point Buy</strong> (27 points). Then assign your{" "}
        <strong className="text-text">background increase</strong> below — the
        final score is base + bonus.
      </Guide>

      {/* Base method toggle */}
      <Segmented
        className="mb-4"
        ariaLabel="Ability score method"
        options={[
          { value: "standard", label: "Standard Array" },
          { value: "pointbuy", label: "Point Buy" },
        ]}
        value={abilityMethod}
        onChange={(v) => setAbilityMethod(v as AbilityMethod)}
      />

      {abilityMethod === "pointbuy" && (
        <div className="mb-4 text-sm">
          Points spent:{" "}
          <span
            className={cn(
              "font-bold tabular",
              spent > POINT_BUY_TOTAL ? "text-danger" : "text-accent"
            )}
          >
            {spent}
          </span>{" "}
          / {POINT_BUY_TOTAL}
        </div>
      )}

      {/* Background ASI sub-panel */}
      <AsiPanel
        background={selectedBackground}
        targets={targets}
        asi={asi}
        onChange={setAsi}
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {ABILITY_NAMES.map((ability) => {
          const bonus = bonuses[ability];
          const finalScore = final[ability];
          const mod = getAbilityModifier(finalScore);
          return (
            <div
              key={ability}
              className="rounded-lg border border-border bg-bg-elevated p-4"
            >
              <div className="mb-1 flex items-center justify-between">
                <span className="text-sm font-bold text-accent">
                  {ABILITY_LABELS[ability]}
                </span>
                <span className="text-lg font-bold text-text tabular">
                  {finalScore}{" "}
                  <span className="text-sm text-text-muted">
                    ({formatModifier(mod)})
                  </span>
                </span>
              </div>
              <p className="mb-2 text-xs text-text-muted">
                {ABILITY_DESCRIPTIONS[ability]}
              </p>

              {abilityMethod === "standard" ? (
                <select
                  aria-label={`Assign value to ${ABILITY_LABELS[ability]}`}
                  value={standardAssignments[ability] ?? ""}
                  onChange={(e) => {
                    const val = e.target.value ? Number(e.target.value) : null;
                    setStandardAssignments((prev) => ({
                      ...prev,
                      [ability]: val,
                    }));
                  }}
                  className={controlClass}
                >
                  <option value="">-- Assign --</option>
                  {STANDARD_ARRAY.map((v) => (
                    <option
                      key={v}
                      value={v}
                      disabled={
                        usedStandard.includes(v) &&
                        standardAssignments[ability] !== v
                      }
                    >
                      {v}
                    </option>
                  ))}
                </select>
              ) : (
                <div className="flex items-center gap-2">
                  <button
                    aria-label={`Decrease ${ABILITY_LABELS[ability]}`}
                    onClick={() =>
                      setBaseAbilities((prev) => ({
                        ...prev,
                        [ability]: Math.max(8, prev[ability] - 1),
                      }))
                    }
                    disabled={baseAbilities[ability] <= 8}
                    className="rounded bg-surface-light px-2.5 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                  >
                    −
                  </button>
                  <span className="w-8 text-center text-sm font-bold tabular">
                    {baseAbilities[ability]}
                  </span>
                  <button
                    aria-label={`Increase ${ABILITY_LABELS[ability]}`}
                    onClick={() =>
                      setBaseAbilities((prev) => ({
                        ...prev,
                        [ability]: Math.min(15, prev[ability] + 1),
                      }))
                    }
                    disabled={baseAbilities[ability] >= 15}
                    className="rounded bg-surface-light px-2.5 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                  >
                    +
                  </button>
                  <span className="ml-auto text-xs text-text-muted">
                    Cost: {POINT_BUY_COSTS[baseAbilities[ability]] ?? 0}
                  </span>
                </div>
              )}

              <div className="mt-1.5 text-xs text-text-muted tabular">
                base {base[ability]}
                {bonus > 0 && (
                  <span className="font-semibold text-gold">
                    {" "}
                    +{bonus} background
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
