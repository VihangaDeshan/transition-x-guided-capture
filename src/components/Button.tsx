// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, type ViewStyle } from 'react-native';

import { theme } from '../guidance/presentation';

interface Props {
  title: string;
  onPress: () => void;
  kind?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
  busy?: boolean;
  style?: ViewStyle;
  testID?: string;
}

export default function Button({ title, onPress, kind = 'primary', disabled, busy, style, testID }: Props) {
  const bg = kind === 'primary' ? theme.accent : kind === 'danger' ? theme.danger : theme.surfaceAlt;
  const fg = kind === 'secondary' ? theme.text : '#0B0E12';
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled || busy}
      style={({ pressed }) => [
        styles.btn,
        { backgroundColor: bg, opacity: disabled ? 0.45 : pressed ? 0.8 : 1 },
        style,
      ]}
    >
      {busy ? <ActivityIndicator color={fg} /> : <Text style={[styles.text, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  btn: { minHeight: 52, borderRadius: 14, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 20 },
  text: { fontSize: 17, fontWeight: '700' },
});
