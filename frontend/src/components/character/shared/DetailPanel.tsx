export default function DetailPanel({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-border bg-bg-elevated p-4">
      <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
        {title}
      </h3>
      {children}
    </div>
  );
}
