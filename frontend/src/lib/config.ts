/** Client-side runtime configuration (Keycloak). Single source for the auth URLs. */

export const KEYCLOAK_URL =
  process.env.NEXT_PUBLIC_KEYCLOAK_URL || "http://localhost:8180";

export const KEYCLOAK_REALM = "dnd-ai";

export const KEYCLOAK_CLIENT_ID = "dnd-ai-frontend";

/** The Keycloak account-management console for the current realm. */
export const ACCOUNT_URL = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/account`;
