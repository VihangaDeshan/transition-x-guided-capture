// SPDX-License-Identifier: Apache-2.0
// FR-8: a visual record of where the user has walked. Replaces a coverage percentage in the first
// release (TDD 7.3), because the obvious percentage rewards spinning in place.
import React, { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { theme } from '../guidance/presentation';
import { projectTrail, type Pt } from '../utils/trail';

interface Props {
  trail: Pt[];
  position: Pt | null;
  size?: number;
  label?: string;
}

const MAX_DOTS = 220;

export default function PoseTrail({ trail, position, size = 120, label }: Props) {
  const p = useMemo(() => {
    const shown = trail.length > MAX_DOTS ? trail.slice(trail.length - MAX_DOTS) : trail;
    const start = trail.length ? trail[0] : null;
    const pr = projectTrail(start && shown[0] !== start ? [start, ...shown] : shown, position, size);
    return pr;
  }, [trail, position, size]);

  return (
    <View style={[styles.box, { width: size, height: size }]} testID="pose-trail">
      {p.points.map(([x, y], i) => (
        <View key={i} style={[styles.dot, { left: x - 2, top: y - 2 }]} />
      ))}
      {p.start ? <View style={[styles.start, { left: p.start[0] - 5, top: p.start[1] - 5 }]} /> : null}
      {p.current ? <View style={[styles.current, { left: p.current[0] - 6, top: p.current[1] - 6 }]} /> : null}
      {label ? <Text style={styles.label}>{label}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: {
    backgroundColor: 'rgba(14,17,22,0.75)',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.15)',
    overflow: 'hidden',
  },
  dot: { position: 'absolute', width: 4, height: 4, borderRadius: 2, backgroundColor: theme.accent },
  start: {
    position: 'absolute', width: 10, height: 10, borderRadius: 5,
    borderWidth: 2, borderColor: theme.text, backgroundColor: 'transparent',
  },
  current: {
    position: 'absolute', width: 12, height: 12, borderRadius: 6,
    backgroundColor: theme.info, borderWidth: 2, borderColor: theme.text,
  },
  label: { position: 'absolute', left: 6, bottom: 4, color: theme.textDim, fontSize: 10 },
});
