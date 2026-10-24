// SPDX-License-Identifier: Apache-2.0
// How a GuidanceState looks and feels. The interface never evaluates thresholds; it only maps
// the engine's decision (severity, escalation) to colour and haptics.
import type { Severity } from '../../modules/guided-capture';

export const theme = {
  bg: '#0E1116',
  surface: '#171B22',
  surfaceAlt: '#1F2530',
  text: '#F2F4F7',
  textDim: '#A3ACB9',
  accent: '#3DD6A3',
  danger: '#FF5C5C',
  warning: '#FFB020',
  info: '#4DA3FF',
  good: '#3DD6A3',
};

export function severityColor(s: Severity | null | undefined, positive = false): string {
  if (positive) return theme.good;
  switch (s) {
    case 'CRITICAL':
      return theme.danger;
    case 'WARNING':
      return theme.warning;
    default:
      return theme.info;
  }
}

/**
 * Vibration patterns (ms): tracking/structural problems get one long pulse, quality warnings two
 * short ones, information none (TDD 6.6, "Don't Look Now").
 */
export function hapticPattern(s: Severity | null | undefined): number[] | null {
  switch (s) {
    case 'CRITICAL':
      return [0, 400];
    case 'WARNING':
      return [0, 80, 90, 80];
    default:
      return null;
  }
}
