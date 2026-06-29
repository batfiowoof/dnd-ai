import Spinner from "./Spinner";
import Alert from "./Alert";

interface DataGateProps {
  query: { isLoading: boolean; isError: boolean };
  loadingLabel: string;
  onRetry: () => void;
  children: React.ReactNode;
}

/**
 * Wraps a React-Query-backed region: shows a spinner while loading, a themed error + retry on
 * failure, and the children once data is ready. Reusable across every data-driven view.
 */
export default function DataGate({
  query,
  loadingLabel,
  onRetry,
  children,
}: DataGateProps) {
  if (query.isLoading) {
    return (
      <div className="flex items-center justify-center gap-3 py-12 text-text-muted">
        <Spinner className="text-accent" /> {loadingLabel}
      </div>
    );
  }
  if (query.isError) {
    return (
      <div className="py-8">
        <Alert>
          Couldn&apos;t load reference data.{" "}
          <button
            onClick={onRetry}
            className="font-semibold text-accent underline hover:text-accent-light"
          >
            Retry
          </button>
        </Alert>
      </div>
    );
  }
  return <>{children}</>;
}
