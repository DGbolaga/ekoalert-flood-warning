import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import './Sheet.css';

const PEEK_HEIGHT = 260;
const SNAP = 56;

/**
 * A bottom sheet with two detents, peek and full. Opaque, hairline top edge, no
 * shadow and no blur: nothing on this map floats over anything.
 */
export function Sheet({
  open,
  onClose,
  children,
  labelledBy,
}: {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  labelledBy?: string;
}) {
  const element = useRef<HTMLDivElement | null>(null);
  const [height, setHeight] = useState(520);
  const [detent, setDetent] = useState<'peek' | 'full'>('peek');
  const [drag, setDrag] = useState<number | undefined>();
  const start = useRef({ y: 0, base: 0 });

  useLayoutEffect(() => {
    const node = element.current;
    if (!node) return;
    const measure = () => setHeight(node.getBoundingClientRect().height);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [open]);

  useEffect(() => {
    if (open) setDetent('peek');
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const restingOffset = detent === 'full' ? 0 : Math.max(height - PEEK_HEIGHT, 0);
  const offset = drag ?? restingOffset;

  const beginDrag = (event: React.PointerEvent) => {
    (event.target as Element).setPointerCapture?.(event.pointerId);
    start.current = { y: event.clientY, base: restingOffset };
    setDrag(restingOffset);
  };

  const moveDrag = (event: React.PointerEvent) => {
    if (drag === undefined) return;
    const next = start.current.base + (event.clientY - start.current.y);
    setDrag(Math.min(Math.max(next, 0), height));
  };

  const endDrag = () => {
    if (drag === undefined) return;
    const peekOffset = Math.max(height - PEEK_HEIGHT, 0);
    if (drag > peekOffset + SNAP) {
      setDrag(undefined);
      onClose();
      return;
    }
    setDetent(drag < peekOffset - SNAP ? 'full' : 'peek');
    setDrag(undefined);
  };

  return (
    <>
      <button className="sheet-scrim" aria-label="Close" onClick={onClose} />
      <div
        ref={element}
        className={`sheet${drag !== undefined ? ' sheet--dragging' : ''}`}
        style={{ transform: `translateY(${offset}px)` }}
        role="dialog"
        aria-modal="false"
        aria-labelledby={labelledBy}
      >
        <div
          className="sheet__grip"
          onPointerDown={beginDrag}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          role="button"
          tabIndex={0}
          aria-label={detent === 'peek' ? 'Expand panel' : 'Collapse panel'}
          aria-expanded={detent === 'full'}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              setDetent((d) => (d === 'peek' ? 'full' : 'peek'));
            }
          }}
        >
          <span className="sheet__grip-bar" />
        </div>
        <button className="sheet__close" type="button" onClick={onClose} aria-label="Close panel">
          <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
            <path d="M4 4l10 10M14 4L4 14" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" />
          </svg>
        </button>
        <div className="sheet__body">{children}</div>
      </div>
    </>
  );
}
