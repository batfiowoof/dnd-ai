/** Step heading + a short worldbuilding tip, consistent with the character wizard's Guide. */
export default function SectionIntro({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mb-4">
      <h2 className="mb-2 text-lg font-bold text-accent">{title}</h2>
      <div className="rounded-lg bg-bg-elevated p-3 text-xs leading-relaxed text-text-muted">
        <strong className="text-text">Worldbuilding tip:</strong> {children}
      </div>
    </div>
  );
}
