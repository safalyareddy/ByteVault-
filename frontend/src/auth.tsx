import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, ApiError, onUnauthorized, tokenStore } from './api/client';
import type { LoginResponse, Role } from './api/types';

interface Session {
  username: string;
  role: Role;
}

interface AuthContextValue {
  session: Session | null;
  /** True while an existing token is being checked on page load. */
  checking: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  expired: boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(() => tokenStore.get() !== null);
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    onUnauthorized(() => {
      tokenStore.set(null);
      setSession((s) => {
        if (s) setExpired(true);
        return null;
      });
    });
    if (tokenStore.get()) {
      api<Session>('/auth/me')
        .then(setSession)
        .catch(() => tokenStore.set(null))
        .finally(() => setChecking(false));
    }
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await api<LoginResponse>('/auth/login', { method: 'POST', body: { username, password } });
    tokenStore.set(res.token);
    setExpired(false);
    setSession({ username: res.username, role: res.role });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api('/auth/logout', { method: 'POST' });
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
    }
    tokenStore.set(null);
    setSession(null);
  }, []);

  return <AuthContext.Provider value={{ session, checking, login, logout, expired }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
