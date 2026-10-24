// SPDX-License-Identifier: Apache-2.0
// FR-1 and TDD section 18: permission, ARCore availability, unsupported device and storage are
// verified before a session can start. An unsupported phone is an expected path, not an error.
import React, { useCallback, useEffect, useState } from 'react';
import { AppState, PermissionsAndroid, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';

import GuidedCapture, { type SupportInfo } from '../../modules/guided-capture';
import Button from '../components/Button';
import { theme } from '../guidance/presentation';
import { uiText, type Lang } from '../i18n/strings';
import { formatBytes } from '../utils/format';

interface Props {
  lang: Lang;
  onToggleLang: () => void;
  onStart: () => void;
}

type Row = { label: string; ok: boolean | null; value: string };

export default function CheckScreen({ lang, onToggleLang, onStart }: Props) {
  const t = (k: Parameters<typeof uiText>[1]) => uiText(lang, k) as string;
  const [info, setInfo] = useState<SupportInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      let s = await GuidedCapture.checkSupport();
      // ARCore may answer UNKNOWN_CHECKING on first call; poll briefly.
      for (let i = 0; i < 10 && s.arcore === 'UNKNOWN_CHECKING'; i++) {
        await new Promise((r) => setTimeout(r, 300));
        s = await GuidedCapture.checkSupport();
      }
      setInfo(s);
      setError(null);
    } catch (e) {
      setError(String((e as Error)?.message ?? e));
    }
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (st) => {
      if (st === 'active') refresh(); // back from the Play Store or the permission screen
    });
    return () => sub.remove();
  }, [refresh]);

  const askCamera = async () => {
    if (Platform.OS !== 'android') return;
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
    refresh();
  };

  const install = async () => {
    setBusy(true);
    try {
      await GuidedCapture.requestArCoreInstall();
    } catch (e) {
      setError(String((e as Error)?.message ?? e));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const arOk = info?.arcore === 'SUPPORTED_INSTALLED';
  const arNeedsInstall = info?.arcore === 'SUPPORTED_NOT_INSTALLED' || info?.arcore === 'SUPPORTED_APK_TOO_OLD';
  const unsupported = info?.arcore === 'UNSUPPORTED_DEVICE_NOT_CAPABLE';
  const storageOk = info ? info.freeBytes < 0 || info.freeBytes > 2_000_000_000 : null;
  const ready = !!info && arOk && info.cameraPermission;

  const rows: Row[] = [
    { label: t('checkCamera'), ok: info ? info.cameraPermission : null, value: info?.cameraPermission ? 'OK' : '—' },
    { label: t('checkArcore'), ok: info ? arOk : null, value: info ? info.arcore.replace(/_/g, ' ').toLowerCase() : '…' },
    { label: t('checkStorage'), ok: storageOk, value: info ? formatBytes(info.freeBytes) : '…' },
  ];

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <View style={{ flex: 1 }}>
          <Text style={styles.title}>{t('appTitle')}</Text>
          <Text style={styles.subtitle}>{t('appSubtitle')}</Text>
        </View>
        <Button title={t('language')} kind="secondary" onPress={onToggleLang} style={styles.langBtn} testID="lang-toggle" />
      </View>

      <View style={styles.card}>
        {info ? <Text style={styles.device}>{info.device} · Android API {info.sdkInt}</Text> : <Text style={styles.device}>{t('checking')}</Text>}
        {rows.map((r) => (
          <View key={r.label} style={styles.row}>
            <View style={[styles.dot, { backgroundColor: r.ok === null ? theme.textDim : r.ok ? theme.good : theme.danger }]} />
            <Text style={styles.rowLabel}>{r.label}</Text>
            <Text style={styles.rowValue}>{r.value}</Text>
          </View>
        ))}
      </View>

      {error ? <Text style={styles.error}>{t('error')}: {error}</Text> : null}

      {unsupported ? (
        <View style={[styles.card, { borderColor: theme.danger }]}>
          <Text style={styles.body}>{t('unsupported')}</Text>
        </View>
      ) : null}

      {info && !info.cameraPermission && !unsupported ? (
        <View style={styles.card}>
          <Text style={styles.body}>{t('cameraDenied')}</Text>
          <Button title={t('grantCamera')} onPress={askCamera} style={{ marginTop: 12 }} />
        </View>
      ) : null}

      {arNeedsInstall ? <Button title={t('installArcore')} onPress={install} busy={busy} style={{ marginTop: 12 }} /> : null}

      {ready ? (
        <View style={styles.card}>
          <Text style={styles.section}>{t('tipsTitle')}</Text>
          {(uiText(lang, 'tips') as string[]).map((tip, i) => (
            <Text key={i} style={styles.tip}>
              {i + 1}. {tip}
            </Text>
          ))}
          <Button title={t('startScan')} onPress={onStart} style={{ marginTop: 16 }} testID="start-scan" />
        </View>
      ) : (
        <Button title={t('recheck')} kind="secondary" onPress={refresh} style={{ marginTop: 12 }} />
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: theme.bg },
  content: { padding: 16, paddingTop: 24, paddingBottom: 48 },
  header: { flexDirection: 'row', alignItems: 'center', marginBottom: 16 },
  title: { color: theme.text, fontSize: 28, fontWeight: '800' },
  subtitle: { color: theme.textDim, fontSize: 15, marginTop: 4 },
  langBtn: { minHeight: 40, paddingHorizontal: 12, marginLeft: 12 },
  card: {
    backgroundColor: theme.surface, borderRadius: 16, padding: 16, marginTop: 12,
    borderWidth: 1, borderColor: 'rgba(255,255,255,0.06)',
  },
  device: { color: theme.textDim, fontSize: 13, marginBottom: 8 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  dot: { width: 10, height: 10, borderRadius: 5, marginRight: 10 },
  rowLabel: { color: theme.text, fontSize: 15, flex: 1 },
  rowValue: { color: theme.textDim, fontSize: 14 },
  body: { color: theme.text, fontSize: 15, lineHeight: 22 },
  section: { color: theme.text, fontSize: 17, fontWeight: '700', marginBottom: 8 },
  tip: { color: theme.textDim, fontSize: 15, lineHeight: 22, marginTop: 4 },
  error: { color: theme.danger, marginTop: 12 },
});
