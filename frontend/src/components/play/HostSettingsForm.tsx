import type { Difficulty, DmLength, DmStyle, TurnMode } from "@/types";
import {
  Field,
  SegmentedControl,
  Switch,
  controlClass,
  cn,
} from "@/components/ui";
import { TURN_MODES } from "@/lib/presetWorlds";

interface HostSettingsFormProps {
  turnMode: TurnMode;
  setTurnMode: (v: TurnMode) => void;
  collabWindowSeconds: number;
  setCollabWindowSeconds: (v: number) => void;
  maxPlayers: number;
  setMaxPlayers: (v: number) => void;
  difficulty: Difficulty;
  setDifficulty: (v: Difficulty) => void;
  dmStyle: DmStyle;
  setDmStyle: (v: DmStyle) => void;
  dmLength: DmLength;
  setDmLength: (v: DmLength) => void;
  allowAiCombat: boolean;
  setAllowAiCombat: (v: boolean) => void;
  allowAiRolls: boolean;
  setAllowAiRolls: (v: boolean) => void;
  allowAiDisposition: boolean;
  setAllowAiDisposition: (v: boolean) => void;
}

/**
 * Host session settings: turn-handling cards, collab round window, party size, difficulty,
 * DM style + narration length, and the two DM feature toggles.
 */
export default function HostSettingsForm({
  turnMode,
  setTurnMode,
  collabWindowSeconds,
  setCollabWindowSeconds,
  maxPlayers,
  setMaxPlayers,
  difficulty,
  setDifficulty,
  dmStyle,
  setDmStyle,
  dmLength,
  setDmLength,
  allowAiCombat,
  setAllowAiCombat,
  allowAiRolls,
  setAllowAiRolls,
  allowAiDisposition,
  setAllowAiDisposition,
}: HostSettingsFormProps) {
  return (
    <>
      {/* Turn handling */}
      <div className="space-y-2">
        <label className="block text-xs font-semibold uppercase tracking-wider text-text-muted">
          Turn Handling
        </label>
        {TURN_MODES.map((m) => (
          <button
            key={m.value}
            type="button"
            onClick={() => setTurnMode(m.value)}
            className={cn(
              "w-full cursor-pointer rounded-lg border px-4 py-3 text-left transition",
              turnMode === m.value
                ? "border-accent bg-accent/10"
                : "border-border bg-bg-elevated hover:border-accent/50"
            )}
          >
            <div className="flex items-center justify-between gap-3">
              <div>
                <p
                  className="text-sm font-semibold text-text"
                  style={{ fontFamily: "var(--font-display)" }}
                >
                  {m.name}
                </p>
                <p className="text-xs text-text-muted">{m.desc}</p>
              </div>
              <span
                aria-hidden
                className={cn(
                  "h-3.5 w-3.5 flex-shrink-0 rounded-full border-2 transition",
                  turnMode === m.value ? "border-gold bg-gold" : "border-border"
                )}
              />
            </div>
          </button>
        ))}
      </div>

      {turnMode === "COLLABORATIVE" && (
        <Field
          label="Round window"
          htmlFor="collab-window"
          hint="How long the round collects actions before the DM resolves it."
        >
          <div className="flex items-center gap-3">
            <input
              id="collab-window"
              type="range"
              min={3}
              max={30}
              step={1}
              value={collabWindowSeconds}
              onChange={(e) => setCollabWindowSeconds(Number(e.target.value))}
              className="flex-1 accent-[var(--color-accent)]"
            />
            <span className="tabular w-12 text-right text-sm text-gold">
              {collabWindowSeconds}s
            </span>
          </div>
        </Field>
      )}

      {/* Party size + difficulty */}
      <div className="grid grid-cols-2 gap-3">
        <Field label="Party Size" htmlFor="party-size">
          <select
            id="party-size"
            value={maxPlayers}
            onChange={(e) => setMaxPlayers(Number(e.target.value))}
            className={controlClass}
          >
            {[1, 2, 3, 4, 5, 6, 7, 8].map((n) => (
              <option key={n} value={n}>
                {n} player{n > 1 ? "s" : ""}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Difficulty">
          <SegmentedControl<Difficulty>
            value={difficulty}
            onChange={setDifficulty}
            options={[
              { value: "EASY", label: "Easy" },
              { value: "NORMAL", label: "Normal" },
              { value: "DEADLY", label: "Deadly" },
            ]}
          />
        </Field>
      </div>

      {/* DM voice */}
      <Field label="DM Style">
        <SegmentedControl<DmStyle>
          value={dmStyle}
          onChange={setDmStyle}
          options={[
            { value: "HEROIC", label: "Heroic" },
            { value: "GRIMDARK", label: "Grimdark" },
            { value: "COMEDIC", label: "Comedic" },
          ]}
        />
      </Field>
      <Field label="Narration Length">
        <SegmentedControl<DmLength>
          value={dmLength}
          onChange={setDmLength}
          options={[
            { value: "CONCISE", label: "Concise" },
            { value: "STANDARD", label: "Standard" },
            { value: "RICH", label: "Rich" },
          ]}
        />
      </Field>

      {/* Feature toggles */}
      <div className="space-y-2">
        <Switch
          label="DM can start combat"
          hint="Lets the DM trigger encounters from the story."
          checked={allowAiCombat}
          onChange={setAllowAiCombat}
        />
        <Switch
          label="DM can request rolls"
          hint="Lets the DM call for ability checks on uncertain actions."
          checked={allowAiRolls}
          onChange={setAllowAiRolls}
        />
        <Switch
          label="DM can adjust NPC relationships"
          hint="Lets the DM change how NPCs feel about the party as the story unfolds."
          checked={allowAiDisposition}
          onChange={setAllowAiDisposition}
        />
      </div>
    </>
  );
}
