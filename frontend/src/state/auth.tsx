import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { configureAuth, login as apiLogin } from '../api/client';
import type { LoginResponse, Role } from '../api/types';

const STORE_KEY = 'ekoalert.session';
const ZONE_KEY = 'ekoalert.reporterZone';

export interface Session {
  token: string;
  role: Role;
  reporterId?: number;
  expiresAt: string;
}

interface AuthValue {
  session?: Session;
  isReporter: boolean;
  isAdmin: boolean;
  /**
   * The contract has no endpoint returning the signed-in reporter's own zone, so
   * this is the zone the server reported back on his last accepted report. It is
   * absent until he has filed one, and the UI says so rather than guessing.
   */
  knownZoneId?: string;
  rememberZone: (zoneId: string) => void;
  signIn: (username: string, password: string) => Promise<LoginResponse>;
  signOut: () => void;
  /** Set when a call came back 401 and the session was dropped underneath us. */
  expired: boolean;
  clearExpired: () => void;
}

const AuthContext = createContext<AuthValue | undefined>(undefined);

function readStored(): Session | undefined {
  try {
    const raw = sessionStorage.getItem(STORE_KEY);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as Session;
    if (typeof parsed.token !== 'string') return undefined;
    if (new Date(parsed.expiresAt).getTime() <= Date.now()) return undefined;
    return parsed;
  } catch {
    return undefined;
  }
}

function zoneKeyFor(reporterId: number): string {
  return `${ZONE_KEY}.${reporterId}`;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | undefined>(readStored);
  const [expired, setExpired] = useState(false);
  const [knownZoneId, setKnownZoneId] = useState<string | undefined>(() => {
    const s = readStored();
    if (s?.reporterId === undefined) return undefined;
    return localStorage.getItem(zoneKeyFor(s.reporterId)) ?? undefined;
  });

  // The token lives in a ref so the client reads the current one without the
  // client module needing to re-subscribe on every render.
  const tokenRef = useRef<string | undefined>(session?.token);
  tokenRef.current = session?.token;

  const signOut = useCallback(() => {
    tokenRef.current = undefined;
    sessionStorage.removeItem(STORE_KEY);
    setSession(undefined);
  }, []);

  useEffect(() => {
    configureAuth(
      () => tokenRef.current,
      () => {
        // A 401 mid-session. There is no refresh endpoint, so drop the session
        // and let the router send him to login. The offline queue is untouched.
        if (tokenRef.current) {
          setExpired(true);
          signOut();
        }
      },
    );
  }, [signOut]);

  const signIn = useCallback(async (username: string, password: string) => {
    const res = await apiLogin(username, password);
    const next: Session = {
      token: res.token,
      role: res.role,
      reporterId: res.reporterId,
      expiresAt: res.expiresAt,
    };
    tokenRef.current = next.token;
    sessionStorage.setItem(STORE_KEY, JSON.stringify(next));
    setSession(next);
    setExpired(false);
    setKnownZoneId(
      next.reporterId === undefined
        ? undefined
        : (localStorage.getItem(zoneKeyFor(next.reporterId)) ?? undefined),
    );
    return res;
  }, []);

  const rememberZone = useCallback(
    (zoneId: string) => {
      if (session?.reporterId === undefined) return;
      localStorage.setItem(zoneKeyFor(session.reporterId), zoneId);
      setKnownZoneId(zoneId);
    },
    [session?.reporterId],
  );

  const value = useMemo<AuthValue>(
    () => ({
      session,
      isReporter: session?.role === 'REPORTER',
      isAdmin: session?.role === 'ADMIN',
      knownZoneId,
      rememberZone,
      signIn,
      signOut,
      expired,
      clearExpired: () => setExpired(false),
    }),
    [session, knownZoneId, rememberZone, signIn, signOut, expired],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth used outside AuthProvider');
  return value;
}
