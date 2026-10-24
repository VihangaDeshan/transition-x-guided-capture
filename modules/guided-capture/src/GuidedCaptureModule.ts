// SPDX-License-Identifier: Apache-2.0
import { NativeModule, requireNativeModule } from 'expo';

import type {
  AcceptResult,
  GuidedCaptureEvents,
  SessionSummary,
  StartConfig,
  StartResult,
  SupportInfo,
} from './GuidedCapture.types';

declare class GuidedCaptureModule extends NativeModule<GuidedCaptureEvents> {
  checkSupport(): Promise<SupportInfo>;
  requestArCoreInstall(): Promise<'INSTALLED' | 'INSTALL_REQUESTED'>;
  startCapture(config: StartConfig): Promise<StartResult>;
  stopCapture(): Promise<SessionSummary>;
  acceptSession(sessionId: string): Promise<AcceptResult>;
  discardSession(sessionId: string): Promise<boolean>;
  sharePackage(sessionId: string): Promise<boolean>;
  setDebugSignals(enabled: boolean): void;
}

export default requireNativeModule<GuidedCaptureModule>('GuidedCapture');
