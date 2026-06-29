/**
 * Lightweight, self-contained UI/game sound engine. All sounds are SYNTHESISED with the
 * Web Audio API — no asset files are shipped, it works fully offline, and every cue is one
 * `playSound(name)` call. Playback is a no-op until the user has (a) left the "Sound effects"
 * setting on (`prefs.sound`, reflected here via `setSoundEnabled`) and (b) made a first
 * gesture so the browser lets us start audio (`unlockAudio`, wired in Providers).
 *
 * Sounds can later be swapped for sampled files behind the same `playSound` API without
 * touching any call sites.
 */

export type SoundName =
  | "click"
  | "toggle"
  | "dice"
  | "diceSettle"
  | "crit"
  | "fumble"
  | "hit"
  | "miss"
  | "heal"
  | "turn"
  | "combatStart"
  | "victory"
  | "defeat";

let enabled = true; // mirrors prefs.sound (set via setSoundEnabled / applyPrefs)
let ctx: AudioContext | null = null;
let master: GainNode | null = null;

/** Reflect the Settings → "Sound effects" toggle. Called from prefs.applyPrefs. */
export function setSoundEnabled(value: boolean): void {
  enabled = value;
}

type WindowWithWebkitAudio = Window &
  typeof globalThis & { webkitAudioContext?: typeof AudioContext };

function ensureCtx(): AudioContext | null {
  if (typeof window === "undefined") return null;
  if (!ctx) {
    const Ctor =
      window.AudioContext ?? (window as WindowWithWebkitAudio).webkitAudioContext;
    if (!Ctor) return null;
    ctx = new Ctor();
    master = ctx.createGain();
    master.gain.value = 0.2; // keep the whole palette tasteful / quiet
    master.connect(ctx.destination);
  }
  return ctx;
}

/**
 * Resume the AudioContext on a user gesture. Browsers create it `suspended`, so the first
 * click/keypress must un-gate it. Safe to call repeatedly.
 */
export function unlockAudio(): void {
  const ac = ensureCtx();
  if (ac && ac.state === "suspended") void ac.resume();
}

/* ── Synth primitives ──────────────────────────────────────────────── */

/** A single enveloped oscillator note. `t0` is an absolute context time. */
function tone(
  ac: AudioContext,
  dest: AudioNode,
  freq: number,
  t0: number,
  dur: number,
  type: OscillatorType = "sine",
  peak = 0.6
): void {
  const osc = ac.createOscillator();
  const gain = ac.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t0);
  gain.gain.setValueAtTime(0.0001, t0);
  gain.gain.exponentialRampToValueAtTime(peak, t0 + 0.008);
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  osc.connect(gain).connect(dest);
  osc.start(t0);
  osc.stop(t0 + dur + 0.02);
}

/** A filtered white-noise burst — the basis for dice/hits/whooshes. */
function noise(
  ac: AudioContext,
  dest: AudioNode,
  t0: number,
  dur: number,
  filterType: BiquadFilterType,
  freqStart: number,
  freqEnd: number,
  peak = 0.5
): void {
  const frames = Math.max(1, Math.floor(ac.sampleRate * dur));
  const buffer = ac.createBuffer(1, frames, ac.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frames; i++) data[i] = Math.random() * 2 - 1;

  const src = ac.createBufferSource();
  src.buffer = buffer;

  const filter = ac.createBiquadFilter();
  filter.type = filterType;
  filter.frequency.setValueAtTime(freqStart, t0);
  filter.frequency.exponentialRampToValueAtTime(Math.max(40, freqEnd), t0 + dur);

  const gain = ac.createGain();
  gain.gain.setValueAtTime(peak, t0);
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);

  src.connect(filter).connect(gain).connect(dest);
  src.start(t0);
  src.stop(t0 + dur + 0.02);
}

/** An ascending/descending arpeggio of notes from a base frequency. */
function arpeggio(
  ac: AudioContext,
  dest: AudioNode,
  base: number,
  steps: number[],
  t0: number,
  stepDur: number,
  type: OscillatorType = "triangle",
  peak = 0.5
): void {
  steps.forEach((semis, i) => {
    const freq = base * Math.pow(2, semis / 12);
    tone(ac, dest, freq, t0 + i * stepDur, stepDur * 1.8, type, peak);
  });
}

/* ── Sound palette ─────────────────────────────────────────────────── */

function render(name: SoundName, ac: AudioContext, out: AudioNode, t: number): void {
  switch (name) {
    case "click":
      tone(ac, out, 880, t, 0.05, "triangle", 0.35);
      break;
    case "toggle":
      tone(ac, out, 520, t, 0.06, "square", 0.3);
      tone(ac, out, 780, t + 0.05, 0.06, "square", 0.3);
      break;
    case "dice":
      // A clatter: a couple of quick noise taps then a short tumble sweep.
      noise(ac, out, t, 0.05, "bandpass", 2600, 1800, 0.5);
      noise(ac, out, t + 0.06, 0.05, "bandpass", 2200, 1500, 0.45);
      noise(ac, out, t + 0.12, 0.14, "highpass", 1200, 600, 0.4);
      break;
    case "diceSettle":
      tone(ac, out, 600, t, 0.12, "triangle", 0.4);
      noise(ac, out, t, 0.08, "lowpass", 1400, 500, 0.35);
      break;
    case "crit":
      arpeggio(ac, out, 523.25, [0, 4, 7, 12], t, 0.07, "triangle", 0.5); // bright C major
      break;
    case "fumble":
      arpeggio(ac, out, 392, [0, -2, -5], t, 0.1, "sawtooth", 0.4); // dull descending
      break;
    case "hit":
      tone(ac, out, 120, t, 0.16, "sine", 0.7); // low thud
      noise(ac, out, t, 0.1, "lowpass", 1800, 400, 0.5);
      break;
    case "miss":
      noise(ac, out, t, 0.22, "bandpass", 1600, 700, 0.4); // whoosh
      break;
    case "heal":
      arpeggio(ac, out, 587.33, [0, 5, 9], t, 0.09, "sine", 0.4); // soft shimmer
      break;
    case "turn":
      tone(ac, out, 784, t, 0.18, "sine", 0.45); // chime
      tone(ac, out, 1175, t + 0.02, 0.16, "sine", 0.3);
      break;
    case "combatStart":
      tone(ac, out, 98, t, 0.4, "sawtooth", 0.5); // ominous drone
      tone(ac, out, 147, t + 0.05, 0.35, "sawtooth", 0.35);
      noise(ac, out, t, 0.25, "lowpass", 900, 200, 0.4);
      break;
    case "victory":
      arpeggio(ac, out, 523.25, [0, 4, 7, 12, 16], t, 0.11, "triangle", 0.5); // fanfare
      break;
    case "defeat":
      arpeggio(ac, out, 440, [0, -3, -7, -12], t, 0.16, "sawtooth", 0.45); // somber fall
      break;
  }
}

/** Play a synthesised cue. No-op when sound is disabled or audio isn't available yet. */
export function playSound(name: SoundName): void {
  if (!enabled) return;
  const ac = ensureCtx();
  if (!ac || !master) return;
  if (ac.state === "suspended") void ac.resume();
  try {
    render(name, ac, master, ac.currentTime + 0.001);
  } catch {
    /* never let a missing audio feature break the UI */
  }
}
