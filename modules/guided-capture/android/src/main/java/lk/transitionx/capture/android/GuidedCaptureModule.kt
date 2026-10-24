// SPDX-License-Identifier: Apache-2.0
// The React Native bridge (TDD v1.0 section 12): session commands down, low-bandwidth events up.
// Full image buffers never cross it.
package lk.transitionx.capture.android

import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class GuidedCaptureModule : Module() {
    private val controller: CaptureController
        get() {
            val ctx = appContext.reactContext ?: throw Exceptions.ReactContextLost()
            return CaptureController.get(ctx).also { c ->
                if (c.emitter == null) c.emitter = { name, body -> sendEvent(name, body) }
            }
        }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CaptureException) {
        throw CodedException(e.code, e.message, e)
    }

    override fun definition() = ModuleDefinition {
        Name("GuidedCapture")

        Events("guidanceChanged", "captureStatus", "signalFrame", "sessionCompleted", "captureError")

        OnCreate {
            appContext.reactContext?.let { ctx ->
                CaptureController.get(ctx).emitter = { name, body -> sendEvent(name, body) }
            }
        }

        AsyncFunction("checkSupport") {
            guarded { controller.checkSupport() }
        }

        AsyncFunction("requestArCoreInstall") {
            guarded { controller.requestInstall(appContext.throwingActivity) }
        }.runOnQueue(Queues.MAIN)

        AsyncFunction("startCapture") { config: Map<String, Any?> ->
            guarded { controller.startCapture(config) }
        }.runOnQueue(Queues.MAIN)

        AsyncFunction("stopCapture") {
            guarded { controller.stopCapture("USER_STOP") }
        }

        AsyncFunction("acceptSession") { sessionId: String ->
            guarded { controller.acceptSession(sessionId) }
        }

        AsyncFunction("discardSession") { sessionId: String ->
            guarded { controller.discardSession(sessionId) }
        }

        AsyncFunction("sharePackage") { sessionId: String ->
            guarded { controller.sharePackage(appContext.throwingActivity, sessionId) }
        }.runOnQueue(Queues.MAIN)

        Function("setDebugSignals") { enabled: Boolean ->
            controller.debugSignals = enabled
        }

        OnActivityEntersForeground {
            appContext.reactContext?.let { CaptureController.get(it).onHostResume() }
        }

        OnActivityEntersBackground {
            appContext.reactContext?.let { CaptureController.get(it).onHostPause() }
        }

        View(GuidedCaptureView::class) {}
    }
}
