// SPDX-License-Identifier: Apache-2.0
// FR-11/12/13/14: summary, then accept (package + manual transfer) or record again while the user
// is still standing in the room.
import React, { useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, View } from 'react-native';

import GuidedCapture, { type AcceptResult, type SessionSummary } from '../../modules/guided-capture';
import Button from '../components/Button';
import PoseTrail from '../components/PoseTrail';
import { severityColor, theme } from '../guidance/presentation';
import { severityForRule } from '../guidance/rules';
import { RULE_IDS, ruleText, uiText, type Lang } from '../i18n/strings';
import { formatBytes, formatDuration, formatMetres } from '../utils/format';

interface Props {
  lang: Lang;
  summary: SessionSummary;
  onRecordAgain: () => void;
  onNewScan: () => void;
}

export default function SummaryScreen({ lang, summary, onRecordAgain, onNewScan }: Props) {
  const t = (k: Parameters<typeof uiText>[1]) => uiText(lang, k) as string;
  const [busy, setBusy] = useState<'accept' | 'again' | 'share' | 'delete' | null>(null);
  const [deleted, setDeleted] = useState(false);
  const [exported, setExported] = useState<AcceptResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const verdictColor =
    summary.verdict === 'GOOD' ? theme.good : summary.verdict === 'CHECK' ? theme.warning : theme.danger;
  const warnings = RULE_IDS.filter((id) => (summary.warningsByRule?.[id] ?? 0) > 0).map((id) => ({
    id,
    count: summary.warningsByRule[id] as number,
  }));

  const accept = async () => {
    setBusy('accept');
    setError(null);
    try {
      setExported(await GuidedCapture.acceptSession(summary.sessionId));
    } catch (e) {
      setError(String((e as Error)?.message ?? e));
    } finally {
      setBusy(null);
    }
  };

  const again = async () => {
    setBusy('again');
    try {
      await GuidedCapture.discardSession(summary.sessionId);
    } catch {
      // nothing to delete is fine
    }
    setBusy(null);
    onRecordAgain();
  };

  const share = async () => {
    setBusy('share');
    try {
      await GuidedCapture.sharePackage(summary.sessionId);
    } catch (e) {
      setError(String((e as Error)?.message ?? e));
    } finally {
      setBusy(null);
    }
  };

  // Privacy: the package is a video of someone's home. Once it has been sent, offer to remove it.
  const deleteLocal = () => {
    Alert.alert(t('deleteTitle'), t('deleteBody'), [
      { text: t('cancel'), style: 'cancel' },
      {
        text: t('deleteConfirm'),
        style: 'destructive',
        onPress: async () => {
          setBusy('delete');
          try {
            await GuidedCapture.discardSession(summary.sessionId);
            setDeleted(true);
          } catch (e) {
            setError(String((e as Error)?.message ?? e));
          } finally {
            setBusy(null);
          }
        },
      },
    ]);
  };

  const metric = (label: string, value: string) => (
    <View style={styles.metric} key={label}>
      <Text style={styles.metricValue}>{value}</Text>
      <Text style={styles.metricLabel}>{label}</Text>
    </View>
  );

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <Text style={styles.title}>{t('summaryTitle')}</Text>
      <View style={[styles.verdict, { borderColor: verdictColor }]}>
        <View style={[styles.verdictDot, { backgroundColor: verdictColor }]} />
        <Text style={styles.verdictText} testID="verdict">
          {t(`verdict${summary.verdict}` as Parameters<typeof uiText>[1])}
        </Text>
      </View>
      {summary.endReason === 'MAX_DURATION' ? <Text style={styles.note}>{t('endMax')}</Text> : null}

      <View style={styles.grid}>
        {metric(t('duration'), formatDuration(summary.durationMs))}
        {metric(t('trackingLosses'), `${summary.trackingLostCount}`)}
        {metric(t('path'), formatMetres(summary.pathLengthM))}
        {metric(t('keyframes'), `${summary.keyframeCount}`)}
        {metric(t('recording'), summary.recordingOk ? t('recordingOk') : t('recordingFailed'))}
        {metric(t('interruptions'), `${summary.interruptions}`)}
        {summary.coverageFraction !== null && summary.coverageFraction !== undefined
          ? metric(t('coverageMeasured'), `${Math.round(summary.coverageFraction * 100)}%`)
          : null}
        {metric(t('loopClosed'), summary.loopClosed ? t('yes') : t('no'))}
      </View>

      <View style={styles.card}>
        <Text style={styles.section}>{t('trail')}</Text>
        <View style={{ alignItems: 'center', marginTop: 8 }}>
          <PoseTrail trail={summary.trail ?? []} position={null} size={220} />
        </View>
        <Text style={styles.small}>
          {t('coverage')}: {summary.coverageMethod}
          {summary.coverageMethod === 'POSE_TRAIL' ? ` — ${t('coverageNote')}` : ''}
        </Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.section}>{t('warnings')}</Text>
        {warnings.length === 0 ? <Text style={styles.small}>{t('noWarnings')}</Text> : null}
        {warnings.map(({ id, count }) => (
          <View style={styles.warnRow} key={id}>
            <View style={[styles.warnTag, { backgroundColor: severityColor(severityForRule(id), id === 'C6') }]}>
              <Text style={styles.warnTagText}>{id}</Text>
            </View>
            <Text style={styles.warnText}>{ruleText(lang, id).short}</Text>
            <Text style={styles.warnCount}>×{count}</Text>
          </View>
        ))}
        {summary.recordingError ? <Text style={[styles.small, { color: theme.danger }]}>{summary.recordingError}</Text> : null}
      </View>

      {error ? <Text style={styles.error}>{error}</Text> : null}

      {exported ? (
        <View style={[styles.card, { borderColor: theme.good }]}>
          <Text style={styles.section}>{t('exportedTitle')}</Text>
          <Text style={styles.small}>{t('exportedBody')}</Text>
          {deleted ? (
            <Text style={styles.small}>{t('deletedNote')}</Text>
          ) : (
            <>
              <Text style={styles.path} selectable>
                {t('savedAt')}: {exported.zipPath} ({formatBytes(exported.zipBytes)})
              </Text>
              <Text style={styles.small}>{t('integrityNote')}</Text>
              <Button title={t('share')} onPress={share} busy={busy === 'share'} style={{ marginTop: 12 }} testID="share" />
              <Button title={t('deleteLocal')} kind="secondary" onPress={deleteLocal} busy={busy === 'delete'} style={{ marginTop: 10 }} testID="delete-local" />
            </>
          )}
          <Button title={t('newScan')} kind="secondary" onPress={onNewScan} style={{ marginTop: 10 }} />
        </View>
      ) : (
        <View style={styles.actions}>
          <Button title={t('useCapture')} onPress={accept} busy={busy === 'accept'} disabled={!!busy} testID="accept" />
          <Button title={t('recordAgain')} kind="secondary" onPress={again} busy={busy === 'again'} disabled={!!busy} style={{ marginTop: 10 }} testID="again" />
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: theme.bg },
  content: { padding: 16, paddingTop: 32, paddingBottom: 48 },
  title: { color: theme.text, fontSize: 26, fontWeight: '800' },
  verdict: {
    flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderRadius: 14,
    padding: 14, marginTop: 14, backgroundColor: theme.surface,
  },
  verdictDot: { width: 14, height: 14, borderRadius: 7, marginRight: 10 },
  verdictText: { color: theme.text, fontSize: 18, fontWeight: '700', flex: 1 },
  note: { color: theme.warning, marginTop: 8 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', marginTop: 12, marginHorizontal: -4 },
  metric: { width: '33.33%', padding: 4 },
  metricValue: {
    color: theme.text, fontSize: 18, fontWeight: '700', backgroundColor: theme.surface,
    paddingTop: 12, paddingHorizontal: 10, borderTopLeftRadius: 12, borderTopRightRadius: 12,
  },
  metricLabel: {
    color: theme.textDim, fontSize: 12, backgroundColor: theme.surface, paddingBottom: 12,
    paddingHorizontal: 10, borderBottomLeftRadius: 12, borderBottomRightRadius: 12,
  },
  card: {
    backgroundColor: theme.surface, borderRadius: 16, padding: 16, marginTop: 12,
    borderWidth: 1, borderColor: 'rgba(255,255,255,0.06)',
  },
  section: { color: theme.text, fontSize: 17, fontWeight: '700' },
  small: { color: theme.textDim, fontSize: 13, marginTop: 8, lineHeight: 19 },
  path: { color: theme.text, fontSize: 12, marginTop: 8, fontFamily: 'monospace' },
  warnRow: { flexDirection: 'row', alignItems: 'center', marginTop: 10 },
  warnTag: { borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2, marginRight: 10, minWidth: 34, alignItems: 'center' },
  warnTagText: { color: '#0B0E12', fontWeight: '800', fontSize: 12 },
  warnText: { color: theme.text, flex: 1, fontSize: 14 },
  warnCount: { color: theme.textDim, fontSize: 14, marginLeft: 8 },
  actions: { marginTop: 16 },
  error: { color: theme.danger, marginTop: 12 },
});
