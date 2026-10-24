// SPDX-License-Identifier: Apache-2.0
import thresholds from '../config/thresholds.v1.json';

/** Keep in step with app.json "version" (checked by a unit test). */
export const APP_VERSION = '1.1.0';

export const THRESHOLDS: Record<string, number | string> = thresholds;
