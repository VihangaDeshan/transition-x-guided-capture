// SPDX-License-Identifier: Apache-2.0
import thresholds from '../config/thresholds.v1.json';

/** Keep in step with app.json "version" (checked by a unit test). */
export const APP_VERSION = '1.0.0';

export const THRESHOLDS: Record<string, number | string> = thresholds;

/** Rough ARCore recording size used for the pre-recording free-space check (NFR-5). */
export const ESTIMATED_BYTES_PER_SECOND = 2_500_000;
