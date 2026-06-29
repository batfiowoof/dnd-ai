import { controlClass, cn, useToast } from "@/components/ui";
import { PRESET_WORLDS } from "@/lib/presetWorlds";

type WorldSource = "preset" | "custom-write" | "custom-upload";

interface WorldSettingPickerProps {
  worldSource: WorldSource;
  setWorldSource: (v: WorldSource) => void;
  selectedPreset: string;
  setSelectedPreset: (id: string) => void;
  customWorldText: string;
  setCustomWorldText: (text: string) => void;
  expandedPreset: string | null;
  setExpandedPreset: (id: string | null) => void;
}

/**
 * World-setting source picker: source tabs (presets / write / upload), the preset cards,
 * the write-your-own textarea, and the .md/.txt upload (incl. its file-read logic).
 */
export default function WorldSettingPicker({
  worldSource,
  setWorldSource,
  selectedPreset,
  setSelectedPreset,
  customWorldText,
  setCustomWorldText,
  expandedPreset,
  setExpandedPreset,
}: WorldSettingPickerProps) {
  const toast = useToast();

  function handleFileUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.name.endsWith(".md") && !file.name.endsWith(".txt")) {
      toast.error("Please upload a .md or .txt file.");
      return;
    }
    const reader = new FileReader();
    reader.onload = (ev) => {
      const text = ev.target?.result;
      if (typeof text === "string") {
        setCustomWorldText(text);
      }
    };
    reader.readAsText(file);
  }

  return (
    <>
      {/* World setting source tabs */}
      <div>
        <label className="mb-2 block text-xs font-semibold uppercase tracking-wider text-text-muted">
          World Setting
        </label>
        <div className="flex rounded-lg border border-border bg-bg-elevated p-1 text-xs">
          {(
            [
              ["preset", "Presets"],
              ["custom-write", "Write"],
              ["custom-upload", "Upload"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              onClick={() => setWorldSource(value)}
              className={cn(
                "flex-1 cursor-pointer rounded-md px-3 py-2 font-medium transition",
                worldSource === value
                  ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
                  : "text-text-muted hover:text-text"
              )}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* Preset world cards */}
      {worldSource === "preset" && (
        <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
          {PRESET_WORLDS.map((world) => (
            <div key={world.id}>
              <button
                onClick={() => setSelectedPreset(world.id)}
                className={cn(
                  "w-full cursor-pointer rounded-lg border px-4 py-3 text-left transition",
                  selectedPreset === world.id
                    ? "border-accent bg-accent/10"
                    : "border-border bg-bg-elevated hover:border-accent/50 hover:-translate-y-0.5"
                )}
              >
                <div className="flex items-center justify-between">
                  <div>
                    <p
                      className="text-sm font-semibold text-text"
                      style={{ fontFamily: "var(--font-display)" }}
                    >
                      {world.name}
                    </p>
                    <p className="text-xs text-text-muted">{world.tagline}</p>
                  </div>
                  <span
                    className={cn(
                      "h-3.5 w-3.5 flex-shrink-0 rounded-full border-2 transition",
                      selectedPreset === world.id
                        ? "border-gold bg-gold"
                        : "border-border"
                    )}
                  />
                </div>
              </button>
              {selectedPreset === world.id && (
                <button
                  onClick={() =>
                    setExpandedPreset(
                      expandedPreset === world.id ? null : world.id
                    )
                  }
                  className="mt-1 ml-1 cursor-pointer text-xs text-accent transition hover:text-accent-light hover:underline"
                >
                  {expandedPreset === world.id ? "Hide details" : "Show details"}
                </button>
              )}
              {expandedPreset === world.id && (
                <div className="mt-1 max-h-40 overflow-y-auto rounded-lg border border-border bg-bg-elevated p-3 text-xs leading-relaxed text-text-muted whitespace-pre-wrap">
                  {world.setting}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Custom write */}
      {worldSource === "custom-write" && (
        <textarea
          value={customWorldText}
          onChange={(e) => setCustomWorldText(e.target.value)}
          placeholder={`Describe your world setting...\n\nInclude key locations, factions, tone, and any rules or themes you want the DM to follow. Markdown is supported.`}
          rows={8}
          className={cn(controlClass, "resize-none")}
        />
      )}

      {/* Custom upload */}
      {worldSource === "custom-upload" && (
        <div className="space-y-3">
          <label className="flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-bg-elevated px-4 py-6 transition hover:border-accent">
            <svg
              className="mb-2 h-8 w-8 text-text-muted"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M12 16.5V9.75m0 0 3 3m-3-3-3 3M6.75 19.5a4.5 4.5 0 0 1-1.41-8.775 5.25 5.25 0 0 1 10.233-2.33 3 3 0 0 1 3.758 3.848A3.752 3.752 0 0 1 18 19.5H6.75Z"
              />
            </svg>
            <span className="text-sm text-text-muted">
              Upload a <span className="text-accent">.md</span> or{" "}
              <span className="text-accent">.txt</span> file
            </span>
            <input
              type="file"
              accept=".md,.txt"
              onChange={handleFileUpload}
              className="hidden"
            />
          </label>
          {customWorldText && (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs text-text-muted">
                  File loaded ({customWorldText.length} characters)
                </span>
                <button
                  onClick={() => setCustomWorldText("")}
                  className="cursor-pointer text-xs text-accent transition hover:text-accent-light hover:underline"
                >
                  Clear
                </button>
              </div>
              <div className="max-h-32 overflow-y-auto rounded-lg border border-border bg-bg-elevated p-3 text-xs text-text-muted whitespace-pre-wrap">
                {customWorldText}
              </div>
            </div>
          )}
        </div>
      )}
    </>
  );
}
