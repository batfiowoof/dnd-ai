"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useRef,
} from "react";
import type { ReactNode } from "react";
import Keycloak from "keycloak-js";

interface AuthContextType {
  username: string | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: () => void;
  logout: () => void;
  register: () => void;
  getToken: () => Promise<string | null>;
}

const AuthContext = createContext<AuthContextType | null>(null);

const keycloakConfig = {
  url: process.env.NEXT_PUBLIC_KEYCLOAK_URL || "http://localhost:8180",
  realm: "dnd-ai",
  clientId: "dnd-ai-frontend",
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const keycloakRef = useRef<Keycloak | null>(null);
  const initRef = useRef(false);

  useEffect(() => {
    if (initRef.current) return;
    initRef.current = true;

    const kc = new Keycloak(keycloakConfig);
    keycloakRef.current = kc;

    kc.init({
      onLoad: "check-sso",
      silentCheckSsoRedirectUri:
        typeof window !== "undefined"
          ? window.location.origin + "/silent-check-sso.html"
          : undefined,
      pkceMethod: "S256",
      checkLoginIframe: false,
    })
      .then((authenticated) => {
        if (authenticated && kc.tokenParsed) {
          setUsername(
            kc.tokenParsed.preferred_username ?? kc.tokenParsed.sub ?? null
          );
          setToken(kc.token ?? null);
          setIsAuthenticated(true);
        }
        setIsLoading(false);
      })
      .catch((err) => {
        console.error("Keycloak init failed:", err);
        setIsLoading(false);
      });

    // Token refresh
    const refreshInterval = setInterval(() => {
      if (kc.authenticated) {
        kc.updateToken(30)
          .then((refreshed) => {
            if (refreshed) {
              setToken(kc.token ?? null);
            }
          })
          .catch(() => {
            console.warn("Token refresh failed, session expired");
            setIsAuthenticated(false);
            setUsername(null);
            setToken(null);
          });
      }
    }, 30000);

    return () => clearInterval(refreshInterval);
  }, []);

  const login = useCallback(() => {
    keycloakRef.current?.login();
  }, []);

  const register = useCallback(() => {
    keycloakRef.current?.register();
  }, []);

  const logout = useCallback(() => {
    keycloakRef.current?.logout({ redirectUri: window.location.origin + "/" });
  }, []);

  const getToken = useCallback(async (): Promise<string | null> => {
    const kc = keycloakRef.current;
    if (!kc?.authenticated) return null;
    try {
      await kc.updateToken(10);
      setToken(kc.token ?? null);
      return kc.token ?? null;
    } catch {
      return null;
    }
  }, []);

  return (
    <AuthContext.Provider
      value={{
        username,
        token,
        isAuthenticated,
        isLoading,
        login,
        logout,
        register,
        getToken,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
