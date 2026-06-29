"use client";

import { Component, type ReactNode } from "react";
import { Button, Panel } from "@/components/ui";

interface Props {
  children: ReactNode;
  /** Optional custom fallback. Defaults to the themed panel below. */
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
}

/**
 * Catches render-time crashes anywhere in the tree and shows a themed fallback instead of a blank
 * white screen. Pairs with `app/error.tsx` (which catches route-segment render errors); this guards
 * the rest of the client tree (providers, portals, event handlers in render). The raw error is
 * logged to the console for debugging but never shown to the user.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: unknown) {
    console.error("Render error caught by ErrorBoundary:", error);
  }

  private handleReload = () => {
    this.setState({ hasError: false });
    if (typeof window !== "undefined") window.location.reload();
  };

  render() {
    if (!this.state.hasError) return this.props.children;
    if (this.props.fallback) return this.props.fallback;

    return (
      <div className="flex min-h-dvh items-center justify-center p-6">
        <Panel corners className="max-w-md p-8 text-center">
          <h1 className="font-display text-2xl font-bold text-accent text-glow">
            Something broke the spell
          </h1>
          <p className="mt-3 text-sm text-text-muted">
            An unexpected error interrupted the page. Your session is safe —
            reloading usually sets things right.
          </p>
          <Button className="mt-6" onClick={this.handleReload}>
            Reload the page
          </Button>
        </Panel>
      </div>
    );
  }
}
