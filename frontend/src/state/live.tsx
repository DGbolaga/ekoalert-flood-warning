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
import { API_BASE, getGraph } from '../api/client';
import type {
  AlertEvent,
  AllClearEvent,
  AlertView,
  EdgeView,
  GraphCounts,
  ZoneStatusEvent,
  ZoneSummary,
} from '../api/types';
import { isSeverity } from '../lib/severity';

export type StreamState = 'connecting' | 'live' | 'down';

export type ActivityItem =
  | { kind: 'alert'; key: string; at: string; alert: AlertView }
  | { kind: 'all-clear'; key: string; at: string; event: AllClearEvent }
  // Zone status is the only thing the public hears during an escalation whose
  // alerts were all held back, so it belongs in the feed. Without it the screen
  // sits empty at the exact moment the system is doing something.
  | { kind: 'zone-status'; key: string; at: string; event: ZoneStatusEvent };

interface LiveValue {
  zones: ZoneSummary[];
  edges: EdgeView[];
  counts?: GraphCounts;
  zoneById: Map<string, ZoneSummary>;
  loading: boolean;
  loadError?: string;
  stream: StreamState;
  activity: ActivityItem[];
  refresh: () => Promise<void>;
  /** Replace one edge from a correction response without refetching the graph. */
  putEdge: (edge: EdgeView) => void;
  /** Record alerts the report response returned, including suppressed ones. */
  recordAlerts: (alerts: AlertView[]) => void;
}

const LiveContext = createContext<LiveValue | undefined>(undefined);

const ACTIVITY_LIMIT = 120;
const ACTIVITY_KEY = 'ekoalert.activity';

/**
 * No endpoint lists past alerts, so the feed can only hold what arrived while the
 * page was open. Keeping it in sessionStorage means a reload does not wipe it,
 * while closing the tab still does, so nothing from yesterday ever comes back
 * looking live.
 */
function readActivity(): ActivityItem[] {
  try {
    const raw = sessionStorage.getItem(ACTIVITY_KEY);
    return raw ? (JSON.parse(raw) as ActivityItem[]) : [];
  } catch {
    return [];
  }
}

function writeActivity(items: ActivityItem[]): void {
  try {
    sessionStorage.setItem(ACTIVITY_KEY, JSON.stringify(items));
  } catch {
    /* a full or refusing store is not worth failing the screen over */
  }
}

function tally(zones: ZoneSummary[], edges: EdgeView[]): GraphCounts {
  return {
    zones: zones.length,
    edges: edges.length,
    inferred: edges.filter((e) => e.confidence === 'INFERRED').length,
    confirmed: edges.filter((e) => e.confidence === 'CONFIRMED').length,
    rejected: edges.filter((e) => e.confidence === 'REJECTED').length,
    blocked: edges.filter((e) => e.blocked).length,
  };
}

export function LiveProvider({ children }: { children: ReactNode }) {
  const [zones, setZones] = useState<ZoneSummary[]>([]);
  const [edges, setEdges] = useState<EdgeView[]>([]);
  const [counts, setCounts] = useState<GraphCounts | undefined>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | undefined>();
  const [stream, setStream] = useState<StreamState>('connecting');
  const [activity, setActivity] = useState<ActivityItem[]>(readActivity);

  const refresh = useCallback(async () => {
    try {
      const graph = await getGraph();
      setZones(graph.zones);
      setEdges(graph.edges);
      setCounts(graph.counts);
      setLoadError(undefined);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'The map could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const pushActivity = useCallback((item: ActivityItem) => {
    setActivity((current) => {
      if (current.some((existing) => existing.key === item.key)) return current;
      const next = [item, ...current].slice(0, ACTIVITY_LIMIT);
      writeActivity(next);
      return next;
    });
  }, []);

  // The stream carries no replay, so a reconnect refetches the graph rather than
  // assuming nothing happened while the connection was gone.
  const wasDown = useRef(false);

  useEffect(() => {
    const source = new EventSource(`${API_BASE}/alerts/stream`);

    const onConnected = () => {
      setStream('live');
      if (wasDown.current) {
        wasDown.current = false;
        void refresh();
      }
    };

    const onOpen = () => setStream('live');

    const onError = () => {
      // EventSource retries on its own. Say so while it is gone rather than
      // showing a stale map as if it were live.
      wasDown.current = true;
      setStream('down');
    };

    const onAlert = (e: MessageEvent<string>) => {
      const data = JSON.parse(e.data) as AlertEvent;
      pushActivity({
        kind: 'alert',
        key: `alert-${data.id}`,
        at: data.firedAt,
        alert: data,
      });
    };

    const onAllClear = (e: MessageEvent<string>) => {
      const data = JSON.parse(e.data) as AllClearEvent;
      pushActivity({
        kind: 'all-clear',
        key: `clear-${data.originZone}-${data.targetZone}-${data.at}`,
        at: data.at,
        event: data,
      });
    };

    const onZoneStatus = (e: MessageEvent<string>) => {
      const data = JSON.parse(e.data) as ZoneStatusEvent;
      pushActivity({
        kind: 'zone-status',
        key: `status-${data.zoneId}-${data.at}`,
        at: data.at,
        event: data,
      });
      setZones((current) =>
        current.map((zone) => {
          if (zone.id !== data.zoneId) return zone;
          if (data.level === 'CLEAR') {
            return { ...zone, status: { active: false, clearedAt: data.at } };
          }
          if (!isSeverity(data.level)) return zone;
          return { ...zone, status: { active: true, level: data.level, escalatedAt: data.at } };
        }),
      );
    };

    source.addEventListener('connected', onConnected);
    source.addEventListener('alert', onAlert as EventListener);
    source.addEventListener('all-clear', onAllClear as EventListener);
    source.addEventListener('zone-status', onZoneStatus as EventListener);
    source.addEventListener('open', onOpen);
    source.addEventListener('error', onError);

    return () => {
      source.removeEventListener('connected', onConnected);
      source.removeEventListener('alert', onAlert as EventListener);
      source.removeEventListener('all-clear', onAllClear as EventListener);
      source.removeEventListener('zone-status', onZoneStatus as EventListener);
      source.removeEventListener('open', onOpen);
      source.removeEventListener('error', onError);
      source.close();
    };
  }, [refresh, pushActivity]);

  const putEdge = useCallback((edge: EdgeView) => {
    setEdges((current) => {
      const index = current.findIndex((e) => e.id === edge.id);
      const next = index === -1 ? [...current, edge] : current.map((e) => (e.id === edge.id ? edge : e));
      return next;
    });
  }, []);

  const recordAlerts = useCallback(
    (alerts: AlertView[]) => {
      alerts.forEach((alert) =>
        pushActivity({ kind: 'alert', key: `alert-${alert.id}`, at: alert.firedAt, alert }),
      );
    },
    [pushActivity],
  );

  // Counts follow the edges once a correction has changed one locally, so the
  // status strip moves on the same tap that turns the line black.
  useEffect(() => {
    if (edges.length === 0 && zones.length === 0) return;
    setCounts((current) => {
      const next = tally(zones, edges);
      if (
        current &&
        current.zones === next.zones &&
        current.edges === next.edges &&
        current.inferred === next.inferred &&
        current.confirmed === next.confirmed &&
        current.rejected === next.rejected &&
        current.blocked === next.blocked
      ) {
        return current;
      }
      return next;
    });
  }, [zones, edges]);

  const zoneById = useMemo(() => new Map(zones.map((z) => [z.id, z])), [zones]);

  const value = useMemo<LiveValue>(
    () => ({
      zones,
      edges,
      counts,
      zoneById,
      loading,
      loadError,
      stream,
      activity,
      refresh,
      putEdge,
      recordAlerts,
    }),
    [zones, edges, counts, zoneById, loading, loadError, stream, activity, refresh, putEdge, recordAlerts],
  );

  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLive(): LiveValue {
  const value = useContext(LiveContext);
  if (!value) throw new Error('useLive used outside LiveProvider');
  return value;
}
