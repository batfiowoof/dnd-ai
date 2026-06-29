export default function Stat({
  value,
  label,
}: {
  value: string | number;
  label: string;
}) {
  return (
    <div>
      <div className="text-2xl font-bold text-gold tabular">{value}</div>
      <div className="text-xs text-text-muted">{label}</div>
    </div>
  );
}
