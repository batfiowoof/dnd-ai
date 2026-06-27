/**
 * Frontend safety net for the DM's structured directive tags. The backend strips them before
 * persisting/broadcasting, but this guarantees the RENDERED narration never contains a `[[…]]`
 * marker — even mid-stream, where the raw accumulation is shown before the authoritative clean
 * text arrives. Pure and idempotent: safe to run on streamed deltas and reloaded history alike.
 *
 * Apply at RENDER time only — do not mutate the stored streaming deltas, so accumulation stays
 * intact and the final DmResponse can reconcile normally.
 */
export function stripDmTags(text: string): string {
  return text
    // complete tags anywhere in the text
    .replace(
      /\[\[\s*(?:ENCOUNTER|ROLL|INSPIRATION|GROUP|CONTEST)\b[\s\S]*?\]\]/gi,
      ""
    )
    // trailing partial tag still streaming (e.g. "[[ENCOUNTER: GOBLIN" with no closing "]]")
    .replace(
      /\[\[\s*(?:ENCOUNTER|ROLL|INSPIRATION|GROUP|CONTEST)\b[^\]]*$/i,
      ""
    )
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trimEnd();
}
