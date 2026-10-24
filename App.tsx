// SPDX-License-Identifier: Apache-2.0
import React, { useCallback, useState } from 'react';
import { StatusBar, StyleSheet, View } from 'react-native';

import type { SessionSummary } from './modules/guided-capture';
import { theme } from './src/guidance/presentation';
import type { Lang } from './src/i18n/strings';
import CaptureScreen from './src/screens/CaptureScreen';
import CheckScreen from './src/screens/CheckScreen';
import SummaryScreen from './src/screens/SummaryScreen';

type Screen = { name: 'check' } | { name: 'capture'; key: number } | { name: 'summary'; summary: SessionSummary };

export default function App() {
  const [lang, setLang] = useState<Lang>('en');
  const [screen, setScreen] = useState<Screen>({ name: 'check' });

  const toCapture = useCallback(() => setScreen({ name: 'capture', key: Date.now() }), []);
  const toCheck = useCallback(() => setScreen({ name: 'check' }), []);
  const toSummary = useCallback((summary: SessionSummary) => setScreen({ name: 'summary', summary }), []);

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" translucent backgroundColor="transparent" />
      {screen.name === 'check' ? (
        <CheckScreen lang={lang} onToggleLang={() => setLang((l) => (l === 'en' ? 'si' : 'en'))} onStart={toCapture} />
      ) : null}
      {screen.name === 'capture' ? (
        <CaptureScreen key={screen.key} lang={lang} onCompleted={toSummary} onExit={toCheck} />
      ) : null}
      {screen.name === 'summary' ? (
        <SummaryScreen lang={lang} summary={screen.summary} onRecordAgain={toCapture} onNewScan={toCheck} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: theme.bg },
});
