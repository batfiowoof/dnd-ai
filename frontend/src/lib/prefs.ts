/**
 * Local, device-scoped display & audio preferences. Stored in localStorage — no
 * backend. Read at render time; `applyPrefs` reflects them onto the document so
 * CSS can react (e.g. forcing reduced motion).
 */

import { setSoundEnabled } from "./sound";

export interface Prefs {
  reduceMotion: boolean;
  sound: boolean;
  /** Root font scale multiplier, e.g. 1 = 100%. */
  textScale: number;
}

export const DEFAULT_PREFS: Prefs = {
  reduceMotion: false,
  sound: true,
  textScale: 1,
};

const KEY = "dnd-prefs";

export function loadPrefs(): Prefs {
  if (typeof window === "undefined") return DEFAULT_PREFS;
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return DEFAULT_PREFS;
    return { ...DEFAULT_PREFS, ...(JSON.parse(raw) as Partial<Prefs>) };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function savePrefs(prefs: Prefs): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(KEY, JSON.stringify(prefs));
  applyPrefs(prefs);
}

/** Reflect preferences onto the document root so global CSS / fonts respond. */
export function applyPrefs(prefs: Prefs): void {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  root.dataset.reduceMotion = prefs.reduceMotion ? "true" : "false";
  root.style.fontSize = `${Math.round(prefs.textScale * 100)}%`;
  setSoundEnabled(prefs.sound);
}
