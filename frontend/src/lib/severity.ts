import type { Severity } from '../api/types';

export const SEVERITY_WORD: Record<Severity, string> = {
  ANKLE: 'Ankle-deep',
  KNEE: 'Knee-deep',
  IMPASSABLE: 'Impassable',
};

export const SEVERITY_SHORT: Record<Severity, string> = {
  ANKLE: 'Ankle',
  KNEE: 'Knee',
  IMPASSABLE: 'Impassable',
};

export const SEVERITY_COLOR: Record<Severity, string> = {
  ANKLE: 'var(--ankle)',
  KNEE: 'var(--knee)',
  IMPASSABLE: 'var(--impassable)',
};

/** Literal values, for the map layers where CSS custom properties are not read. */
export const SEVERITY_HEX: Record<Severity, string> = {
  ANKLE: '#C79A2E',
  KNEE: '#D2621C',
  IMPASSABLE: '#9E2B25',
};

export const SEVERITY_WASH: Record<Severity, string> = {
  ANKLE: 'var(--ankle-wash)',
  KNEE: 'var(--knee-wash)',
  IMPASSABLE: 'var(--impassable-wash)',
};

/** ANKLE < KNEE < IMPASSABLE. Water decays one step per hop. */
export const SEVERITY_RANK: Record<Severity, number> = {
  ANKLE: 1,
  KNEE: 2,
  IMPASSABLE: 3,
};

export function isSeverity(value: string): value is Severity {
  return value === 'ANKLE' || value === 'KNEE' || value === 'IMPASSABLE';
}
