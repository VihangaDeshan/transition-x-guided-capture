// SPDX-License-Identifier: Apache-2.0
import { requireNativeView } from 'expo';
import * as React from 'react';
import type { ViewProps } from 'react-native';

const NativeView: React.ComponentType<ViewProps> = requireNativeView('GuidedCapture');

/** Hosts the ARCore camera surface. Mounting it opens the AR session; unmounting releases it. */
export default function GuidedCaptureView(props: ViewProps) {
  return <NativeView {...props} />;
}
