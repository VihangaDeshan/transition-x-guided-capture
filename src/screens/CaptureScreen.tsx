// SPDX-License-Identifier: Apache-2.0
// Live capture: AR view, one guidance banner, status line, pose trail and a single large control.
import React, { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { BackHandler, Pressable, StatusBar, StyleSheet, Text, Vibration, View } from 'react-native';

import GuidedCapture, { GuidedCaptureView, type SessionSummary } from '../../modules/guided-capture';
import Button from '../components/Button';
import DebugOverlay from '../components/DebugOverlay';
import GuidanceBanner from '../components/GuidanceBanner';
import PoseTrail from '../components/PoseTrail';
import StatusLine from '../components/StatusLine';
import { APP_VERSION, THRESHOLDS } from '../config';
import { hapticPattern, theme } from '../guidance/presentation';
import { uiText, type Lang } from '../i18n/strings';
import { captureReducer, initialCaptureState, nearSessionLimit } from '../state/captureState';

interface Props {
  lang: Lang;
  onCompleted: (summary: SessionSummary) => void;
  onExit: () => void;
}

export default function CaptureScreen({ lang, onCompleted, onExit }: Props) {
  const t = (k: Parameters<typeof uiText>[1]) => uiText(lang, k) as string;
  const [state, dispatch] = useReducer(captureReducer, initialCaptureState);
  const [debug, setDebug] = useState(false);
  const completedRef = useRef(false);
  const lastRuleRef = useRef<string | null>(null);

  const finish = useCallback(
    (summary: SessionSummary) => {
      if (completedRef.current) return;
      completedRef.current = true;
      onCompleted(summary);
    },
    [onCompleted],
  );

  useEffect(() => {
    const subs = [
      GuidedCapture.addListener('guidanceChanged', (g) => {
        dispatch({ type: 'guidance', payload: g });
        if (g.ruleId && g.ruleId !== lastRuleRef.current) {
          const pattern = hapticPattern(g.severity);
          if (pattern) Vibration.vibrate(pattern);
        }
        lastRuleRef.current = g.ruleId;
      }),
      GuidedCapture.addListener('captureStatus', (s) => dispatch({ type: 'status', payload: s })),
      GuidedCapture.addListener('signalFrame', (s) => dispatch({ type: 'signal', payload: s })),
      GuidedCapture.addListener('sessionCompleted', (s) => finish(s)), // also covers auto-stop at the limit
      GuidedCapture.addListener('captureError', (e) => dispatch({ type: 'error', message: `${e.code}: ${e.message}` })),
    ];
    return () => subs.forEach((s) => s.remove());
  }, [finish]);

  useEffect(() => {
    GuidedCapture.setDebugSignals(debug);
  }, [debug]);

  // Do not leave the screen by accident while recording.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (state.phase === 'recording' || state.phase === 'stopping' || state.phase === 'starting') return true;
      onExit();
      return true;
    });
    return () => sub.remove();
  }, [state.phase, onExit]);

  const start = async () => {
    dispatch({ type: 'start' });
    try {
      await GuidedCapture.startCapture({
        thresholds: THRESHOLDS,
        appVersion: APP_VERSION,
        debugSignals: debug,
      });
      dispatch({ type: 'started' });
    } catch (e) {
      dispatch({ type: 'error', message: String((e as Error)?.message ?? e) });
    }
  };

  const stop = async () => {
    dispatch({ type: 'stop' });
    try {
      const summary = await GuidedCapture.stopCapture();
      finish(summary);
    } catch (e) {
      // Already stopped (e.g. by the time limit): sessionCompleted will deliver the summary.
      dispatch({ type: 'error', message: String((e as Error)?.message ?? e) });
    }
  };

  const recording = state.phase === 'recording';
  const top = (StatusBar.currentHeight ?? 24) + 8;

  return (
    <View style={styles.root}>
      <GuidedCaptureView style={StyleSheet.absoluteFill} />

      <View style={[styles.top, { paddingTop: top }]} pointerEvents="box-none">
        <StatusLine phase={state.phase} elapsedMs={state.elapsedMs} sessionState={state.sessionState} lang={lang} />
        <View style={{ height: 12 }} />
        {state.phase !== 'preview' ? (
          <GuidanceBanner guidance={state.guidance} trackingFailure={state.trackingFailure} lang={lang} />
        ) : null}
        {nearSessionLimit(state) ? <Text style={styles.limit}>{t('oneMinuteLeft')}</Text> : null}
        {state.error ? <Text style={styles.error}>{state.error}</Text> : null}
        {debug ? (
          <View style={{ marginTop: 10 }}>
            <DebugOverlay signal={state.signal} guidance={state.guidance} />
          </View>
        ) : null}
      </View>

      <View style={styles.bottom} pointerEvents="box-none">
        <View style={styles.bottomRow}>
          <PoseTrail trail={state.trail} position={state.position} size={110} label={t('trail')} />
          <View style={styles.controls}>
            {state.phase === 'preview' ? (
              <Button title={t('record')} kind="danger" onPress={start} style={styles.big} testID="record" />
            ) : (
              <Button
                title={state.phase === 'stopping' ? t('stopping') : t('stop')}
                kind={state.canStop ? 'primary' : 'danger'}
                onPress={stop}
                busy={state.phase === 'stopping' || state.phase === 'starting'}
                disabled={!recording}
                style={styles.big}
                testID="stop"
              />
            )}
          </View>
          <Pressable onPress={() => setDebug((d) => !d)} style={styles.debugBtn} accessibilityRole="button">
            <Text style={[styles.debugText, debug && { color: theme.accent }]}>{t('debug')}</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  top: { position: 'absolute', left: 0, right: 0, top: 0 },
  bottom: { position: 'absolute', left: 0, right: 0, bottom: 0, paddingBottom: 36, paddingHorizontal: 16 },
  bottomRow: { flexDirection: 'row', alignItems: 'flex-end' },
  controls: { flex: 1, alignItems: 'center', paddingHorizontal: 12 },
  big: { minWidth: 150, minHeight: 64, borderRadius: 32 },
  debugBtn: { padding: 8, backgroundColor: 'rgba(14,17,22,0.6)', borderRadius: 8 },
  debugText: { color: theme.textDim, fontSize: 12, fontWeight: '600' },
  limit: { color: theme.warning, textAlign: 'center', marginTop: 8, fontWeight: '700' },
  error: { color: theme.danger, marginTop: 8, marginHorizontal: 16, backgroundColor: 'rgba(0,0,0,0.6)', padding: 8, borderRadius: 8 },
});
