// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import type { SessionState } from '../../modules/guided-capture';
import { theme } from '../guidance/presentation';
import { uiText, type Lang } from '../i18n/strings';
import type { Phase } from '../state/captureState';
import { formatDuration } from '../utils/format';

interface Props {
  phase: Phase;
  elapsedMs: number;
  sessionState: SessionState;
  lang: Lang;
}

export default function StatusLine({ phase, elapsedMs, sessionState, lang }: Props) {
  const recording = phase === 'recording' || phase === 'stopping';
  const pill =
    sessionState === 'SCANNING'
      ? { text: uiText(lang, 'tracking'), color: theme.good }
      : sessionState === 'TRACKING_LOST'
        ? { text: uiText(lang, 'trackingLost'), color: theme.danger }
        : sessionState === 'INITIALIZING'
          ? { text: uiText(lang, 'initialising'), color: theme.warning }
          : null;
  return (
    <View style={styles.row}>
      {recording ? (
        <View style={styles.rec}>
          <View style={styles.recDot} />
          <Text style={styles.recText}>{uiText(lang, 'rec')}</Text>
          <Text style={styles.time}>{formatDuration(elapsedMs)}</Text>
        </View>
      ) : (
        <View />
      )}
      {pill ? (
        <View style={[styles.pill, { borderColor: pill.color }]}>
          <View style={[styles.pillDot, { backgroundColor: pill.color }]} />
          <Text style={styles.pillText}>{pill.text}</Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16 },
  rec: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(14,17,22,0.75)',
    borderRadius: 20, paddingHorizontal: 12, paddingVertical: 6,
  },
  recDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: theme.danger, marginRight: 6 },
  recText: { color: theme.danger, fontWeight: '800', marginRight: 8, letterSpacing: 1 },
  time: { color: theme.text, fontVariant: ['tabular-nums'], fontWeight: '600' },
  pill: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(14,17,22,0.75)',
    borderRadius: 20, paddingHorizontal: 10, paddingVertical: 5, borderWidth: 1,
  },
  pillDot: { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  pillText: { color: theme.text, fontSize: 13, fontWeight: '600' },
});
