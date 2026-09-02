import { useMemo, useRef, useState } from 'react';
import './ProposeJunction.css';
import { ApiError, affirmPlace, isOffline, proposeEdge, proposePlace } from '../api/client';
import type { CorrectionResponse, EdgeView, PlaceView, ZoneSummary } from '../api/types';
import { accuracyWord, currentFix, distanceMeters, distanceWord, type Fix } from '../lib/geo';
import { PlacePicker } from './PlacePicker';
import { learnThreshold } from '../lib/threshold';

/** Far enough down the list and it stops being a memory and starts being a guess. */
const CANDIDATE_LIMIT = 12;

type Stage = 'shut' | 'choosing' | 'naming';

export interface ProposeJunctionProps {
  zone: ZoneSummary;
  /** Outbound edges already recorded from this zone, so they are not offered twice. */
  outbound: EdgeView[];
  zones: ZoneSummary[];
  /** Places already named downstream of this zone, still waiting on voices. */
  places: PlaceView[];
  canCorrect: boolean;
  onNeedsSignIn: () => void;
  onProposed: (response: CorrectionResponse) => void;
  onPlaceChanged: (place: PlaceView) => void;
}

/**
 * The one question inference cannot answer.
 *
 * Junction edges between corridors were left blank on purpose, so a zone with
 * nothing downstream is not a gap in the data. It is the open question, and the
 * resident standing in it is the only person who can close it. That is why the
 * terminus version of this is loud and the rest of the sheet is quiet.
 *
 * Often the honest answer is not on the list at all, because the pilot stops at
 * 20 seeded zones and water does not. So the list is a shortcut, not the whole
 * vocabulary: anyone can name somewhere the map has never heard of.
 */
export function ProposeJunction({
  zone,
  outbound,
  zones,
  places,
  canCorrect,
  onNeedsSignIn,
  onProposed,
  onPlaceChanged,
}: ProposeJunctionProps) {
  const [stage, setStage] = useState<Stage>('shut');
  const [busy, setBusy] = useState<string | undefined>();
  const [result, setResult] = useState<string | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [landmark, setLandmark] = useState('');
  const [fix, setFix] = useState<Fix | undefined>();
  const [locating, setLocating] = useState(false);
  const [picking, setPicking] = useState(false);
  const listRef = useRef<HTMLDivElement | null>(null);

  // Same rule the map draws termini by: a rejected edge is not an answer.
  const isTerminus = outbound.every((edge) => edge.confidence === 'REJECTED');

  const candidates = useMemo(() => {
    const taken = new Set(outbound.map((edge) => edge.toZone));
    return zones
      .filter((other) => other.id !== zone.id && !taken.has(other.id))
      .map((other) => ({ zone: other, metres: distanceMeters(zone, other) }))
      .sort((a, b) => a.metres - b.metres)
      .slice(0, CANDIDATE_LIMIT);
  }, [zones, outbound, zone]);

  // Only places claimed downstream of this zone. Affirming one means agreeing
  // with that specific claim, not merely that the place exists somewhere.
  const waiting = useMemo(
    () => places.filter((place) => place.fromZone === zone.id && place.status === 'pending'),
    [places, zone.id],
  );

  // Far enough downstream that the warning it would carry is useless anyway. The
  // server refuses these too; this is so nobody gets that far without noticing.
  const far = fix !== undefined && distanceMeters(zone, fix) > 5000;

  const guard = (): boolean => {
    // The control is visible whether or not he is signed in. Seeing that the
    // question can be answered is half of what makes anyone answer it.
    if (!canCorrect) {
      onNeedsSignIn();
      return false;
    }
    return true;
  };

  const openList = () => {
    setStage('choosing');
    // The block sits at the bottom of a long sheet, so the list it reveals
    // opens below the fold unless it is brought up.
    requestAnimationFrame(() => {
      listRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    });
  };

  const fail = (err: unknown) => {
    if (isOffline(err)) setError('That did not reach the server. Try again when you have signal.');
    else if (err instanceof ApiError) setError(err.message);
    else setError('That did not go through.');
  };

  const sendEdge = async (target: ZoneSummary) => {
    if (!guard()) return;
    setBusy(target.id);
    setError(undefined);
    setResult(undefined);
    try {
      const response = await proposeEdge(zone.id, target.id);
      learnThreshold(response.threshold);
      setResult(edgeOutcomeWord(response, target.displayName));
      setStage('shut');
      onProposed(response);
    } catch (err) {
      fail(err);
    } finally {
      setBusy(undefined);
    }
  };

  const locate = async () => {
    setLocating(true);
    setError(undefined);
    try {
      const got = await currentFix();
      setFix(got);
      // If the map is open, the fix moves the crosshair rather than sitting
      // beside it, so there is only ever one answer to where the place is.
      setPicking(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Your location is not available.');
    } finally {
      setLocating(false);
    }
  };

  const sendPlace = async () => {
    if (!guard()) return;
    const named = landmark.trim();
    if (!named) {
      setError('Give it a name somebody nearby would recognise.');
      return;
    }
    setBusy('place');
    setError(undefined);
    try {
      const place = await proposePlace(zone.id, named, fix);
      learnThreshold(place.threshold);
      setResult(placeOutcomeWord(place, named));
      setStage('shut');
      setLandmark('');
      setFix(undefined);
      setPicking(false);
      onPlaceChanged(place);
    } catch (err) {
      fail(err);
    } finally {
      setBusy(undefined);
    }
  };

  /** Affirming somebody else's place, carrying a fix if it still needs one. */
  const sendAffirm = async (place: PlaceView) => {
    if (!guard()) return;
    setBusy(`place-${place.id}`);
    setError(undefined);
    setResult(undefined);
    try {
      let carried: Fix | undefined;
      if (!place.located) {
        try {
          carried = await currentFix();
        } catch {
          // A voice without a fix still counts. It just cannot promote alone.
        }
      }
      const updated = await affirmPlace(place.id, carried);
      learnThreshold(updated.threshold);
      setResult(placeOutcomeWord(updated, place.landmark));
      setStage('shut');
      onPlaceChanged(updated);
    } catch (err) {
      fail(err);
    } finally {
      setBusy(undefined);
    }
  };

  if (candidates.length === 0 && waiting.length === 0 && stage === 'shut' && !result) return null;

  return (
    <section className={`pj${isTerminus ? ' pj--terminus' : ''}`}>
      {isTerminus && (
        <>
          <h3 className="pj__title">The map stops here</h3>
          <p className="pj__body">
            Nobody has recorded where water goes after {zone.displayName}. No map can work
            this out. Somebody who has watched it has to say.
          </p>
        </>
      )}

      {result && (
        <p className="pj__result" role="status">
          {result}
        </p>
      )}

      {stage === 'shut' &&
        (isTerminus && !result ? (
          <button className="btn btn--wide pj__open" type="button" onClick={openList}>
            Say where water goes next
          </button>
        ) : (
          <button className="pj__quiet" type="button" onClick={openList}>
            {result ? 'Water also goes somewhere else' : 'Water also goes somewhere else from here'}
          </button>
        ))}

      {stage === 'choosing' && (
        <>
          <p className="pj__body">Where does water from here reach next?</p>
          <div className="pj__list" role="list" ref={listRef}>
            {candidates.map(({ zone: other, metres }) => (
              <button
                key={other.id}
                type="button"
                role="listitem"
                className="pj__option"
                disabled={busy !== undefined}
                onClick={() => void sendEdge(other)}
              >
                <span className="pj__option-name">
                  <span className="t17">{other.displayName}</span>
                  <span className="t13 muted" style={{ display: 'block' }}>
                    {other.corridor}
                  </span>
                </span>
                <span className="pj__option-dist">
                  {busy === other.id ? 'Sending' : distanceWord(metres)}
                </span>
              </button>
            ))}
          </div>

          {waiting.length > 0 && (
            <>
              <p className="pj__pending-title">Named by residents, not on the map yet</p>
              <div className="pj__pending" role="list">
                {waiting.map((place) => (
                  <button
                    key={place.id}
                    type="button"
                    role="listitem"
                    className="pj__pending-row"
                    disabled={busy !== undefined}
                    onClick={() => void sendAffirm(place)}
                  >
                    <span className="pj__pending-name">
                      <span className="t17">{place.landmark}</span>
                      <span className="pj__pending-votes">
                        {place.distinctVoices} of {place.threshold} people
                        {place.located ? '' : ', nobody has pinned it yet'}
                      </span>
                    </span>
                    <span className="pj__pending-act">
                      {busy === `place-${place.id}`
                        ? 'Sending'
                        : place.located
                          ? 'Yes, it is real'
                          : 'Pin it, I am here'}
                    </span>
                  </button>
                ))}
              </div>
            </>
          )}

          <button className="pj__quiet" type="button" onClick={() => setStage('naming')}>
            It goes somewhere not on this list
          </button>
        </>
      )}

      {stage === 'naming' && (
        <div className="pj__form">
          <label className="pj__label" htmlFor="pj-landmark">
            What do people call the place water reaches?
          </label>
          <input
            id="pj-landmark"
            className="field"
            type="text"
            value={landmark}
            autoComplete="off"
            placeholder="Alapere Bus Stop"
            onChange={(event) => setLandmark(event.target.value)}
          />

          {!picking && (
            <button
              type="button"
              className="pj__gps"
              onClick={() => setPicking(true)}
            >
              <span className="pj__gps-mark" aria-hidden="true" />
              <span className="pj__gps-text">
                Point to it on the map
                <span className="pj__gps-note">If you can see it, this is the exact way</span>
              </span>
            </button>
          )}

          {picking && (
            <PlacePicker
              origin={zone}
              value={fix ? { lat: fix.lat, lng: fix.lng } : undefined}
              onChange={(at) => setFix({ ...at, accuracyM: 0 })}
            />
          )}

          <button
            type="button"
            className={`pj__gps${fix && fix.accuracyM > 0 ? ' pj__gps--on' : ''}`}
            onClick={() => void locate()}
            disabled={locating}
          >
            <span className="pj__gps-mark" aria-hidden="true" />
            <span className="pj__gps-text">
              {locating
                ? 'Finding you'
                : fix && fix.accuracyM > 0
                  ? 'Moved to where you are'
                  : 'I am standing there, use my location'}
              {fix && fix.accuracyM > 0 && (
                <span className="pj__gps-note">{accuracyWord(fix.accuracyM)}</span>
              )}
            </span>
          </button>

          {far && (
            <p className="pj__warn" role="status">
              That spot is {distanceWord(distanceMeters(zone, fix!))} from {zone.displayName}.
              Water taking that long is past what this map is useful for. Check the cross is
              on the right place.
            </p>
          )}

          <p className="pj__hint">
            The name alone is worth sending. Without a position it cannot be drawn yet, so
            somebody standing there will be asked to pin it.
          </p>

          <div className="pj__actions">
            <button
              className="btn"
              type="button"
              onClick={() => {
                setStage('choosing');
                setError(undefined);
              }}
            >
              Back
            </button>
            <button
              className="btn btn--primary"
              type="button"
              onClick={() => void sendPlace()}
              disabled={busy !== undefined}
            >
              {busy === 'place' ? 'Sending' : 'Add this place'}
            </button>
          </div>
        </div>
      )}

      {error && (
        <p className="pj__error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

/**
 * A proposal that crosses the threshold creates the edge `inferred`, not
 * confirmed, so the copy must not imply anybody will now be warned.
 */
function edgeOutcomeWord(response: CorrectionResponse, targetName: string): string {
  if (response.edge && !response.thresholdMet) {
    return 'That connection is already on the map. Your tap was recorded.';
  }
  if (response.thresholdMet) {
    return `Added. ${response.distinctVoices} people say water goes from here to ${targetName}, so it is drawn on the map now. It stays faint, and warns nobody, until somebody confirms the timing holds.`;
  }
  return `Recorded. ${response.distinctVoices} of ${response.threshold} people say water goes from here to ${targetName}.`;
}

/**
 * A place has two gates, not one, and the copy has to say which is outstanding.
 * Being believed is not the same as being locatable.
 */
function placeOutcomeWord(place: PlaceView, named: string): string {
  if (place.promoted && place.mergedInto) {
    const label = place.zone?.displayName ?? 'a zone already on the map';
    return `That turned out to be ${label}, which the map already had. Your name for it is recorded, and water from here to it is now drawn.`;
  }
  if (place.promoted) {
    return `Added. ${named} is on the map now, and water reaching it from here is drawn faint. It warns nobody until somebody confirms the timing.`;
  }
  if (!place.located) {
    return `Recorded. ${place.distinctVoices} of ${place.threshold} people name this place. It still needs somebody standing there to pin it before it can be drawn.`;
  }
  return `Recorded. ${place.distinctVoices} of ${place.threshold} people name this place.`;
}
