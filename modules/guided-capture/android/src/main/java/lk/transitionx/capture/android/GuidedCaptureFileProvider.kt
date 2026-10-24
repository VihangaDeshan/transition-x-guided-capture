// SPDX-License-Identifier: Apache-2.0
package lk.transitionx.capture.android

import androidx.core.content.FileProvider

/** Own subclass so the manifest entry cannot collide with another library's FileProvider. */
class GuidedCaptureFileProvider : FileProvider()
