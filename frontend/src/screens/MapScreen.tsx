import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import './MapScreen.css';
import { FloodMap } from '../map/FloodMap';
import { ZoneSheet } from './ZoneSheet';
import { Sheet } from '../components/Sheet';
import { EdgeMark } from '../components/EdgeMark';
import { useLive } from '../state/live';
import type { EdgeView } from '../api/types';

export function MapScreen() {
  const { zones, edges, counts, loading, loadError, stream } = useLive();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  const [selected, setSelected] = useState<{ zoneId: string; edgeId?: number } | undefined>();
  const [explaining, setExplaining] = useState(false);
  const [celebrate, setCelebrate] = useState<number | undefined>();

  const openZone = useCallback((zoneId: string) => setSelected({ zoneId }), []);

  // A reporter arriving from the acknowledgement lands with his own zone open, so
  // the connections he has just been asked to confirm are already in front of him.
  useEffect(() => {
    const requested = params.get('zone');
    if (!requested) return;
    setSelected({ zoneId: requested });
    setParams({}, { replace: true });
  }, [params, setParams]);
  const openEdge = useCallback(
    (edge: EdgeView) => setSelected({ zoneId: edge.fromZone, edgeId: edge.id }),
    [],
  );

  return (
    <div className="mapscreen">
      <FloodMap
        zones={zones}
        edges={edges}
        onSelectZone={openZone}
        onSelectEdge={openEdge}
        selectedZoneId={selected?.zoneId}
        celebrateEdgeId={celebrate}
        onCelebrationDone={() => setCelebrate(undefined)}
      />

      <div className="mapscreen__top">
        {loading ? (
          <div className="skel" style={{ height: 46, borderRadius: 'var(--r-button)' }} />
        ) : (
          <button className="strip" type="button" onClick={() => setExplaining(true)}>
            <span className="strip__text">
              {loadError ? loadError : summarise(counts)}
            </span>
            <span className="strip__hint" aria-hidden="true">
              ?
            </span>
            <span className="visually-hidden">Why unconfirmed connections send no warnings</span>
          </button>
        )}

        {/* Never silently pretend to be live. */}
        {stream === 'down' && (
          <p className="offline" role="status">
            <span className="strip__dot" />
            Live updates are disconnected. Reconnecting.
          </p>
        )}
      </div>

      <ZoneSheet
        zoneId={selected?.zoneId}
        focusEdgeId={selected?.edgeId}
        onClose={() => setSelected(undefined)}
        onNeedsSignIn={() => navigate('/login')}
        onEdgeFlipped={setCelebrate}
      />

      <Sheet open={explaining} onClose={() => setExplaining(false)} labelledBy="explain-title">
        <div className="explain">
          <h2 id="explain-title">Most of this map is still a guess</h2>
          <p>
            Every connection here was drawn by taking the next zone downstream along the same
            waterway. It is a rule applied to a map, not something anybody has watched happen.
          </p>
          <p>
            So the map is complete and the system is nearly silent. A connection only sends warnings
            once people who live there have confirmed that water really does travel that way. Until
            then it is drawn, but it is not trusted.
          </p>

          <div className="explain__legend">
            <div className="explain__legend-row">
              <EdgeMark edge={legendEdge('CONFIRMED', false)} />
              <span>Confirmed by residents. This one sends warnings.</span>
            </div>
            <div className="explain__legend-row">
              <EdgeMark edge={legendEdge('INFERRED', false)} />
              <span>Guessed from the map. Nothing is sent along it.</span>
            </div>
            <div className="explain__legend-row">
              <EdgeMark edge={legendEdge('INFERRED', true)} />
              <span>Somebody reported the drain here blocked.</span>
            </div>
          </div>

          <p>
            Tap any zone to see the connections into and out of it. If you know the area, one tap
            tells the system whether the water really goes that way.
          </p>
        </div>
      </Sheet>
    </div>
  );
}

function summarise(counts: ReturnType<typeof useLive>['counts']): string {
  if (!counts) return 'Loading the network';
  const drawn = counts.edges - counts.rejected;
  return `${counts.zones} zones · ${drawn} connections · ${counts.confirmed} confirmed`;
}

function legendEdge(confidence: EdgeView['confidence'], blocked: boolean): EdgeView {
  return {
    id: -1,
    fromZone: '',
    toZone: '',
    travelMinutes: 0,
    confidence,
    blocked,
    alertable: confidence === 'CONFIRMED' && !blocked,
    updatedAt: '',
  };
}
