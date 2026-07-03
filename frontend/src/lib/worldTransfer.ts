import type { WorldCreateUpdateRequest, WorldDto } from "@/types";
import { draftToRequest, slugify, worldToDraft } from "@/lib/worldBuilder";

/**
 * Portable world export/import. A world round-trips through the same mappers the World Builder uses
 * ({@link worldToDraft} → {@link draftToRequest}), so an exported file is exactly the create payload —
 * re-importable, human-readable, and backend-free (no new endpoints; import just calls createWorld).
 */

/** File suffix used for exported worlds — distinctive so the import picker can hint the format. */
export const WORLD_FILE_SUFFIX = ".dndworld.json";

/** Shape an authored world into the clean, re-importable create payload. */
export function worldToExport(world: WorldDto): WorldCreateUpdateRequest {
  return draftToRequest(worldToDraft(world));
}

/** Trigger a browser download of a world as pretty-printed JSON. */
export function downloadWorld(world: WorldDto): void {
  const json = JSON.stringify(worldToExport(world), null, 2);
  const blob = new Blob([json], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${slugify(world.name) || "world"}${WORLD_FILE_SUFFIX}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * Parse an imported world file into a create payload. Tolerates either a raw exported object or an
 * `{ world: {...} }` envelope, and normalises through the editor's mappers so partial or older files
 * import cleanly. Throws a friendly {@link Error} the caller can surface via the toast when the file
 * isn't a recognisable world.
 */
export function parseWorldImport(text: string): WorldCreateUpdateRequest {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error("That file isn't valid JSON. Export a world to see the expected format.");
  }
  const obj =
    parsed && typeof parsed === "object" && "world" in parsed
      ? (parsed as { world: unknown }).world
      : parsed;
  const name = (obj as { name?: unknown } | null)?.name;
  if (!obj || typeof obj !== "object" || typeof name !== "string" || !name.trim()) {
    throw new Error("This doesn't look like a world file — it's missing a world name.");
  }
  // worldToDraft only reads authorable fields (defaulting missing arrays), so a create payload,
  // a full DTO, or a hand-trimmed file all normalise the same way.
  return draftToRequest(worldToDraft(obj as WorldDto));
}
