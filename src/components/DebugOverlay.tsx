// SPDX-License-Identifier: Apache-2.0
// Shows why a message appeared, not only that it did (TDD 12, signalFrame event).
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import type { GuidancePayload, SignalPayload } from '../../modules/guided-capture';

interface Props {
  signal: SignalPayload | null;
  guidance: GuidancePayload | null;
}

const f = (n: number | undefined, d = 1) => (n === undefined || n === null ? '—' : Number(n).toFixed(d));

export default function DebugOverlay({ signal: s, guidance: g }: Props) {
  if (!s) return null;
  const lines = [
    `rule ${g?.ruleId ?? '-'} ${g?.severity ?? ''} ${g?.escalated ? 'ESC' : ''} state ${g?.sessionState ?? ''}`,
    `track ${s.trackingOk ? 'ok' : 'LOST'} ${s.failureReason}  thermal ${s.thermalStatus}`,
    `blur ${f(s.blurScore, 0)}  bright ${f(s.brightness, 0)}  clipHi ${f(s.clippedHigh)}%  img ${s.imageAgeMs}ms`,
    `ang ${f(s.angularVelocity)}°/s  lin ${f(s.linearVelocity, 2)} m/s  jit ${f(s.jitterDegPerSec)}`,
    `sharp ${f(s.sharpness, 2)}  texture ${f(s.textureFraction, 2)}  feat ${s.featureCount}`,
    `spin ${f(s.windowRotationDeg, 0)}° / ${f(s.windowTranslationM, 2)} m`,
    `kf ${s.keyframeCount}  path ${f(s.pathLengthM)} m  base ${f(s.baseline, 2)}  every ${s.sampleInterval}${s.imageSuspended ? ' (img off)' : ''}`,
    `acc ${f(s.angularAccel, 0)}°/s²  af ${s.afState}  glare ${f(s.specularPercent)}%  depth ${s.distanceToSurface == null ? '—' : f(s.distanceToSurface, 2) + ' m'}`,
    `voxels ${s.coverageObserved} cov ${s.coverageObserved ? Math.round((100 * s.coverageCovered) / s.coverageObserved) : 0}%  unseen ${f(s.unseenArcDeg, 0)}°  1-angle ${f(s.viewSingleAngleFraction, 2)}  start ${f(s.distanceFromStartM)} m`,
  ];
  return (
    <View style={styles.box} pointerEvents="none" testID="debug-overlay">
      {lines.map((l, i) => (
        <Text key={i} style={styles.text}>
          {l}
        </Text>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { backgroundColor: 'rgba(0,0,0,0.65)', padding: 8, borderRadius: 8, marginHorizontal: 16 },
  text: { color: '#C8FACC', fontFamily: 'monospace', fontSize: 11, lineHeight: 15 },
});
