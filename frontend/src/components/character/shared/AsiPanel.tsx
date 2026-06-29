import { Field, controlClass } from "@/components/ui";
import {
  asiValid,
  type AbilityName,
  type AsiAssignment,
  type AsiMode,
  type BackgroundInfo,
} from "@/lib/dnd5e";
import { ABILITY_LABELS } from "@/lib/characterCreation";
import Segmented from "./Segmented";

/** The background ability-score-increase sub-panel (the ASI split + validation). */
export default function AsiPanel({
  background,
  targets,
  asi,
  onChange,
}: {
  background: BackgroundInfo | null;
  targets: AbilityName[];
  asi: AsiAssignment;
  onChange: (next: AsiAssignment) => void;
}) {
  if (!background) {
    return (
      <div className="mb-4 rounded-lg border border-dashed border-border bg-bg-elevated p-4 text-xs text-text-muted">
        Choose a background first to assign its ability-score increase.
      </div>
    );
  }

  const valid = asiValid(background, asi);
  const setMode = (mode: AsiMode) =>
    onChange({ ...asi, mode, plusTwo: null, plusOne: null });

  return (
    <div className="mb-4 rounded-lg border border-border bg-bg-elevated p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-text-muted">
          {background.name} Ability Increase
        </h3>
        <Segmented
          ariaLabel="Background ability increase split"
          options={[
            { value: "two-one", label: "+2 / +1" },
            { value: "all-one", label: "+1 / +1 / +1" },
          ]}
          value={asi.mode}
          onChange={(v) => setMode(v as AsiMode)}
        />
      </div>

      {asi.mode === "all-one" ? (
        <p className="text-xs text-text-muted">
          Each of{" "}
          <span className="font-semibold text-gold">
            {background.abilityScores.join(", ")}
          </span>{" "}
          gains +1.
        </p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="+2 to" htmlFor="asi-plus-two">
            <select
              id="asi-plus-two"
              value={asi.plusTwo ?? ""}
              onChange={(e) =>
                onChange({
                  ...asi,
                  plusTwo: (e.target.value || null) as AbilityName | null,
                })
              }
              className={controlClass}
            >
              <option value="">-- choose --</option>
              {targets.map((a) => (
                <option key={a} value={a} disabled={asi.plusOne === a}>
                  {ABILITY_LABELS[a]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="+1 to" htmlFor="asi-plus-one">
            <select
              id="asi-plus-one"
              value={asi.plusOne ?? ""}
              onChange={(e) =>
                onChange({
                  ...asi,
                  plusOne: (e.target.value || null) as AbilityName | null,
                })
              }
              className={controlClass}
            >
              <option value="">-- choose --</option>
              {targets.map((a) => (
                <option key={a} value={a} disabled={asi.plusTwo === a}>
                  {ABILITY_LABELS[a]}
                </option>
              ))}
            </select>
          </Field>
        </div>
      )}

      <p role="status" className="mt-2 text-xs">
        {valid ? (
          <span className="text-success">Ability increase assigned.</span>
        ) : (
          <span className="text-text-muted">
            {asi.mode === "two-one"
              ? "Assign +2 and +1 to two different abilities to continue."
              : "Select a split to continue."}
          </span>
        )}
      </p>
    </div>
  );
}
