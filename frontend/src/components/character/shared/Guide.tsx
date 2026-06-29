export default function Guide({ children }: { children: React.ReactNode }) {
  return (
    <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
      <strong className="text-text">D&amp;D 2024 Guide:</strong> {children}
    </div>
  );
}
