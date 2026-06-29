import { Spinner } from "@/components/ui";
import FeatIcon from "./FeatIcon";

/** The origin-feat callout card shown when a background is selected. */
export default function FeatCallout({
  featName,
  desc,
  loading,
}: {
  featName: string;
  desc?: string;
  loading: boolean;
}) {
  return (
    <div className="rounded-lg border border-gold/40 bg-gold/5 p-4">
      <div className="mb-1 flex items-center gap-2">
        <FeatIcon className="h-4 w-4 text-gold" />
        <span className="text-[10px] font-semibold uppercase tracking-wider text-gold">
          Origin Feat
        </span>
      </div>
      <div className="mb-1 text-sm font-bold text-text">{featName}</div>
      {loading ? (
        <span className="flex items-center gap-2 text-xs text-text-muted">
          <Spinner className="h-3 w-3" /> Consulting the tomes…
        </span>
      ) : desc ? (
        <p className="text-xs leading-relaxed text-text-muted">{desc}</p>
      ) : (
        <p className="text-xs text-text-muted">
          Grants the {featName} origin feat.
        </p>
      )}
    </div>
  );
}
