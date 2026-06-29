export default function ReviewRow({
  label,
  value,
  strong,
}: {
  label: string;
  value: string;
  strong?: boolean;
}) {
  return (
    <div className="text-sm">
      <span className="text-text-muted">{label}:</span>{" "}
      <span className={strong ? "font-semibold text-text" : "text-text"}>
        {value}
      </span>
    </div>
  );
}
