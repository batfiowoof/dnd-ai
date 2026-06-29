import { Button } from "@/components/ui";
import type { PlacingSpell } from "./types";

/** AoE placement banner shown above the board while aiming an area spell. */
export default function AoeBanner({
  placingSpell,
  onCancelAoe,
}: {
  placingSpell: PlacingSpell | null;
  onCancelAoe: () => void;
}) {
  if (!placingSpell) return null;
  return (
    <div className="mb-2 flex items-center justify-between gap-2 rounded-lg border border-gold/50 bg-gold-muted px-3 py-1.5">
      <span className="text-xs text-gold">
        Placing{" "}
        <span className="font-semibold">{placingSpell.name}</span> — click a
        square to aim
      </span>
      <Button type="button" size="sm" variant="ghost" onClick={onCancelAoe}>
        Cancel
      </Button>
    </div>
  );
}
