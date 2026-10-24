// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import type { GuidancePayload, TrackingFailure } from '../../modules/guided-capture';
import { severityColor, theme } from '../guidance/presentation';
import { failureHint, ruleText, type Lang } from '../i18n/strings';

interface Props {
  guidance: GuidancePayload | null;
  trackingFailure: TrackingFailure;
  lang: Lang;
}

/** One message at a time (FR-6). Text is resolved from the rule identifier. */
export default function GuidanceBanner({ guidance, trackingFailure, lang }: Props) {
  if (!guidance || !guidance.ruleId) return null;
  const id = guidance.ruleId;
  const text = ruleText(lang, id, guidance.direction);
  const positive = id === 'C6';
  const color = severityColor(guidance.severity, positive);
  const hint = id === 'T1' ? failureHint(lang, trackingFailure) : '';
  const detail = guidance.escalated ? text.detail : undefined;
  return (
    <View
      style={[styles.banner, { borderColor: color }]}
      accessibilityRole="alert"
      accessibilityLiveRegion="assertive"
      testID={`banner-${id}`}
    >
      <View style={[styles.stripe, { backgroundColor: color }]} />
      <View style={styles.body}>
        <Text style={styles.short}>{text.short}</Text>
        {hint ? <Text style={styles.detail}>{hint}</Text> : null}
        {detail ? <Text style={styles.detail}>{detail}</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    backgroundColor: 'rgba(14,17,22,0.88)',
    borderRadius: 14,
    borderWidth: 1.5,
    overflow: 'hidden',
    marginHorizontal: 16,
  },
  stripe: { width: 6 },
  body: { flex: 1, paddingVertical: 12, paddingHorizontal: 14 },
  short: { color: theme.text, fontSize: 19, fontWeight: '700', lineHeight: 25 },
  detail: { color: theme.textDim, fontSize: 15, marginTop: 6, lineHeight: 21 },
});
