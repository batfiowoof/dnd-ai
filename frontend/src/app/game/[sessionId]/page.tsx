import { redirect } from "next/navigation";

/**
 * The standalone /game chat page was an earlier duplicate of the lobby chat room and does
 * not support the host-configurable turn modes (it gated input on the narrative pointer, so
 * under the COLLABORATIVE default only the host could type, and it had no Pass / ROUND_STATUS
 * / ROLL_REQUEST handling). The combined lobby/[sessionId] page is the single source of truth
 * per CLAUDE.md, so this route now redirects there.
 */
export default async function GameRedirect({
  params,
}: {
  params: Promise<{ sessionId: string }>;
}) {
  const { sessionId } = await params;
  redirect(`/lobby/${sessionId}`);
}
