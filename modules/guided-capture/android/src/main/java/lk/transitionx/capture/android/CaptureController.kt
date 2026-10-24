// SPDX-License-Identifier: Apache-2.0
// Layer 1 (AR capture) and session control. Owns the ARCore session, runs the per-frame loop on
// the GL thread, controls the Recording API, and finalises the export package on stop.
package lk.transitionx.capture.android

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import lk.transitionx.capture.core.CaptureFrame
import lk.transitionx.capture.core.CapturePose
import lk.transitionx.capture.core.CoverageState
import lk.transitionx.capture.core.TrackingFailure
import lk.transitionx.capture.core.TrackingStatus
import lk.transitionx.capture.export.ExportDocs
import lk.transitionx.capture.guidance.Thresholds
import lk.transitionx.capture.replay.JsonLite

class CaptureException(val code: String, message: String) : Exception(message)

class CaptureController private constructor(private val context: Context) {

    /** Set by the Expo module; events go to JavaScript through it. */
    @Volatile var emitter: ((String, Map<String, Any?>) -> Unit)? = null
    @Volatile var debugSignals: Boolean = false

    private val sessionLock = ReentrantLock()
    @Volatile private var session: Session? = null
    private var sessionResumed = false
    private var view: GuidedCaptureView? = null
    private var hostPaused = false

    // GL thread state
    private val background = BackgroundRenderer()
    private var boundSession: Session? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile private var geometryDirty = true

    @Volatile private var capture: ActiveCapture? = null
    private val exporter = PackageExporter(context) { baseDir() }
    private var chosenCameraConfig: CameraConfig? = null

    private class ActiveCapture(
        val sessionId: String,
        val dir: File,
        val thresholds: Thresholds,
        val logger: SessionLogger,
        val pipeline: AnalysisPipeline,
        val createdAtIso: String,
        val appVersion: String,
        var recordingOk: Boolean,
        var recordingError: String?
    ) {
        var lastTimestamp = 0L
        var frameIndex = 0L
        var interruptions = 0
        var camera: ExportDocs.CameraInfo? = null
        @Volatile var stopping = false
    }

    private fun emit(name: String, body: Map<String, Any?>) {
        try {
            @Suppress("UNCHECKED_CAST")
            emitter?.invoke(name, jsSafe(body) as Map<String, Any?>)
        } catch (e: Exception) {
            Log.w(TAG, "emit $name failed", e)
        }
    }

    /** JavaScript numbers are doubles: convert Long/Float/Int recursively so nothing is lost or rejected. */
    private fun jsSafe(v: Any?): Any? = when (v) {
        null -> null
        is Boolean, is String, is Double -> v
        is Number -> v.toDouble()
        is Enum<*> -> v.name
        is Map<*, *> -> v.entries.associate { it.key.toString() to jsSafe(it.value) }
        is List<*> -> v.map { jsSafe(it) }
        is FloatArray -> v.map { it.toDouble() }
        else -> v.toString()
    }

    // ------------------------------------------------------------------ support checks (FR-1)

    fun checkSupport(): Map<String, Any?> {
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context).name
        } catch (e: Exception) {
            "UNKNOWN_ERROR"
        }
        val camera = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return mapOf(
            "arcore" to availability,
            "cameraPermission" to camera,
            "freeBytes" to freeBytes().toDouble(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "sdkInt" to Build.VERSION.SDK_INT,
            "arcoreApkVersion" to arcoreApkVersion()
        )
    }

    /** Must run on the main thread. Returns INSTALLED or INSTALL_REQUESTED. */
    fun requestInstall(activity: Activity): String = try {
        ArCoreApk.getInstance().requestInstall(activity, true).name
    } catch (e: Exception) {
        throw CaptureException("ERR_ARCORE_INSTALL", e.message ?: e.javaClass.simpleName)
    }

    private fun arcoreApkVersion(): String? = try {
        context.packageManager.getPackageInfo("com.google.ar.core", 0).versionName
    } catch (e: Exception) {
        null
    }

    private fun baseDir(): File = context.getExternalFilesDir(null) ?: context.filesDir

    private fun freeBytes(): Long = try { StatFs(baseDir().absolutePath).availableBytes } catch (e: Exception) { -1L }

    // ------------------------------------------------------------------ view and session lifecycle

    /** Main thread: a capture view appeared. Creates and resumes the ARCore session. */
    fun attachView(v: GuidedCaptureView) {
        view = v
        try {
            // ARCore samples create the session with the Activity; fall back to the app context.
            if (session == null) session = createSession(v.appContext.currentActivity ?: context)
            resumeSession()
            v.glView.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "session start failed", e)
            emit("captureError", mapOf("code" to "ERR_SESSION", "message" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /** Main thread: the capture view went away. Ends any capture and releases the camera. */
    fun detachView(v: GuidedCaptureView) {
        if (view !== v) return
        if (capture != null) {
            // Finalise synchronously so the recording is closed before the session is released.
            try { stopCapture("VIEW_DETACHED") } catch (e: Exception) { Log.w(TAG, "stop on detach", e) }
        }
        v.glView.onPause()
        view = null
        releaseSession()
    }

    fun onHostPause() {
        hostPaused = true
        view?.glView?.onPause()
        sessionLock.withLock {
            try { session?.pause() } catch (e: Exception) { Log.w(TAG, "pause", e) }
            sessionResumed = false
        }
        capture?.let {
            it.interruptions++
            it.logger.note(mapOf("type" to "INTERRUPTION", "event" to "paused", "wallMs" to System.currentTimeMillis()))
        }
    }

    fun onHostResume() {
        if (!hostPaused) return
        hostPaused = false
        val v = view ?: return
        try {
            resumeSession()
            v.glView.onResume()
            capture?.logger?.note(mapOf("type" to "INTERRUPTION", "event" to "resumed", "wallMs" to System.currentTimeMillis()))
        } catch (e: Exception) {
            emit("captureError", mapOf("code" to "ERR_RESUME", "message" to (e.message ?: "resume failed")))
        }
    }

    fun release() {
        view?.glView?.onPause()
        view = null
        releaseSession()
    }

    private fun createSession(sessionContext: Context): Session {
        val s = Session(sessionContext)
        try {
            val filter = CameraConfigFilter(s)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
            val configs = s.getSupportedCameraConfigs(filter)
            // The ARCore recording stores the CPU image stream, not the display texture: the first
            // A55 capture was 640x480 although the texture was 1920x1080. Prefer the largest CPU
            // image up to MAX_CPU_IMAGE_PIXELS (720p), then the largest texture up to 1080p.
            val best = configs
                .filter { it.textureSize.width * it.textureSize.height <= 1920 * 1080 }
                .filter { it.imageSize.width * it.imageSize.height <= MAX_CPU_IMAGE_PIXELS }
                .maxWithOrNull(compareBy<CameraConfig>(
                    { it.imageSize.width * it.imageSize.height },
                    { it.textureSize.width * it.textureSize.height }))
            if (best != null) {
                s.cameraConfig = best
                chosenCameraConfig = best
            } else {
                chosenCameraConfig = s.cameraConfig
            }
        } catch (e: Exception) {
            Log.w(TAG, "camera config selection failed; using default", e)
            chosenCameraConfig = try { s.cameraConfig } catch (_: Exception) { null }
        }
        val config = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
        }
        s.configure(config)
        geometryDirty = true
        return s
    }

    private fun resumeSession() {
        sessionLock.withLock {
            val s = session ?: return
            if (!sessionResumed) {
                s.resume()
                sessionResumed = true
                geometryDirty = true
            }
        }
    }

    private fun releaseSession() {
        sessionLock.withLock {
            val s = session ?: return
            try { s.pause() } catch (e: Exception) { Log.w(TAG, "pause", e) }
            try { s.close() } catch (e: Exception) { Log.w(TAG, "close", e) }
            session = null
            sessionResumed = false
        }
    }

    // ------------------------------------------------------------------ GL thread

    fun onSurfaceCreated() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        boundSession = null
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        geometryDirty = true
    }

    fun onDrawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Never block the GL thread behind a control operation; skip the frame instead.
        if (!sessionLock.tryLock()) return
        try {
            val s = session ?: return
            if (!sessionResumed || !background.isReady) return
            if (boundSession !== s) {
                s.setCameraTextureName(background.textureId)
                boundSession = s
                background.invalidateGeometry()
            }
            if (geometryDirty && viewportWidth > 0) {
                s.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
                geometryDirty = false
            }
            val frame = try {
                s.update()
            } catch (e: SessionPausedException) {
                return
            } catch (e: CameraNotAvailableException) {
                capture?.let { reportCameraUnavailable(it) }
                return
            }
            background.draw(frame)
            capture?.let { if (!it.stopping) processFrame(it, frame) }
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        } finally {
            sessionLock.unlock()
        }
    }

    /**
     * Measured on the Galaxy A55: 90.8 MB for 83.5 s at 640x480 (about 1.09 MB/s, H.264 plus
     * IMU tracks). Scaled by CPU image pixels, with 25 % margin.
     */
    private fun estimatedRecordingBytesPerSecond(): Long {
        val size = try { chosenCameraConfig?.imageSize } catch (_: Exception) { null }
        val pixels = if (size != null) size.width.toLong() * size.height else 640L * 480
        return (1_090_000L * pixels / (640L * 480) * 5 / 4).coerceAtLeast(500_000L)
    }

    private fun displayRotation(): Int = try {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (e: Exception) {
        0
    }

    private fun reportCameraUnavailable(cap: ActiveCapture) {
        val now = System.nanoTime()
        cap.pipeline.offer(CaptureFrame(now, CapturePose.IDENTITY, TrackingStatus.PAUSED,
            TrackingFailure.CAMERA_UNAVAILABLE, null, 0, 0, 0, null, 0))
    }

    /** Builds a CaptureFrame from an ARCore frame. Cheap work only; analysis is elsewhere. */
    private fun processFrame(cap: ActiveCapture, frame: Frame) {
        val ts = frame.timestamp
        if (ts == 0L || ts == cap.lastTimestamp) return // no new camera image this update
        cap.lastTimestamp = ts
        cap.frameIndex++

        val camera = frame.camera
        val arPose = camera.pose
        val pose = CapturePose(arPose.tx(), arPose.ty(), arPose.tz(), arPose.qx(), arPose.qy(), arPose.qz(), arPose.qw())
        val status = when (camera.trackingState) {
            TrackingState.TRACKING -> TrackingStatus.TRACKING
            TrackingState.PAUSED -> TrackingStatus.PAUSED
            else -> TrackingStatus.STOPPED
        }
        val failure = if (status == TrackingStatus.TRACKING) TrackingFailure.NONE else mapFailure(camera.trackingFailureReason)

        var featureCount = 0
        if (status == TrackingStatus.TRACKING) {
            try {
                val pc = frame.acquirePointCloud()
                try { featureCount = pc.points.remaining() / 4 } finally { pc.close() }
            } catch (e: Exception) { /* not available this frame */ }
            if (cap.camera == null) cap.camera = readIntrinsics(frame)
        }

        var luma: ByteArray? = null
        var w = 0; var h = 0; var stride = 0
        val p = cap.pipeline
        if (!p.imageSuspended && cap.frameIndex % p.sampleInterval == 0L && p.lumaBusy.compareAndSet(false, true)) {
            try {
                val image = frame.acquireCameraImage()
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    w = image.width; h = image.height; stride = plane.rowStride
                    val needed = stride * (h - 1) + w
                    val dst = p.lumaBufferFor(needed)
                    buf.rewind()
                    buf.get(dst, 0, minOf(needed, buf.remaining()))
                    luma = dst
                } finally {
                    image.close()
                }
            } catch (e: NotYetAvailableException) {
                // first frames: CPU image not ready
            } catch (e: Exception) {
                Log.w(TAG, "camera image", e)
            }
            if (luma == null) p.lumaBusy.set(false)
        }

        cap.logger.pose(ts, pose, status, failure)
        p.offer(CaptureFrame(ts, pose, status, failure, luma, w, h, stride, null, featureCount))
    }

    private fun mapFailure(r: TrackingFailureReason): TrackingFailure = when (r) {
        TrackingFailureReason.NONE -> TrackingFailure.NONE
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingFailure.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingFailure.INSUFFICIENT_FEATURES
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingFailure.INSUFFICIENT_LIGHT
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingFailure.CAMERA_UNAVAILABLE
        else -> TrackingFailure.BAD_STATE
    }

    private fun readIntrinsics(frame: Frame): ExportDocs.CameraInfo? = try {
        val intr = frame.camera.imageIntrinsics
        val f = intr.focalLength
        val c = intr.principalPoint
        val d = intr.imageDimensions
        val cfg = chosenCameraConfig
        val fps = cfg?.fpsRange?.upper ?: 30
        ExportDocs.CameraInfo(
            imageWidth = d[0], imageHeight = d[1],
            textureWidth = cfg?.textureSize?.width ?: 0, textureHeight = cfg?.textureSize?.height ?: 0,
            fps = fps, fx = f[0], fy = f[1], cx = c[0], cy = c[1]
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ commands (TDD section 12)

    /** Main thread. Opens a capture: storage check, logs, analysis, ARCore recording. */
    fun startCapture(config: Map<String, Any?>): Map<String, Any?> {
        if (capture != null) throw CaptureException("ERR_BUSY", "A capture is already running")
        val s = session ?: throw CaptureException("ERR_NO_SESSION", "The camera is not ready yet")

        @Suppress("UNCHECKED_CAST")
        val thresholds = (config["thresholds"] as? Map<String, Any?>)?.let { Thresholds.fromMap(it) } ?: Thresholds()
        debugSignals = config["debugSignals"] as? Boolean ?: false
        val appVersion = config["appVersion"] as? String ?: "dev"
        val bytesPerSecond = (config["estimatedBytesPerSecond"] as? Number)?.toLong()?.coerceIn(100_000L, 50_000_000L)
            ?: estimatedRecordingBytesPerSecond()

        // NFR-5: verify free space against the estimate for a maximum-length session.
        val required = bytesPerSecond * (thresholds.maxSessionMs / 1000) + 50L * 1024 * 1024
        val free = freeBytes()
        if (free in 0 until required) {
            throw CaptureException("ERR_STORAGE",
                "Not enough free space: about ${required / (1024 * 1024)} MB needed, ${free / (1024 * 1024)} MB free")
        }

        val id = UUID.randomUUID().toString()
        val dir = exporter.newSessionDir(id)
        val logger = SessionLogger(dir)
        val pipeline = AnalysisPipeline(
            context, thresholds, logger,
            emit = { n, b -> emit(n, b) },
            debugSignals = { debugSignals },
            onMaxDuration = {
                Thread { try { stopCapture("MAX_DURATION") } catch (e: Exception) { Log.w(TAG, "auto stop", e) } }.start()
            }
        )

        var recordingOk = false
        var recordingError: String? = null
        sessionLock.withLock {
            try {
                s.startRecording(
                    RecordingConfig(s)
                        .setMp4DatasetUri(Uri.fromFile(File(dir, VIDEO_FILE)))
                        .setAutoStopOnPause(false)
                )
                recordingOk = s.recordingStatus == RecordingStatus.OK
                if (!recordingOk) recordingError = "Recording status ${s.recordingStatus}"
            } catch (e: Exception) {
                recordingError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "startRecording failed", e)
            }
            capture = ActiveCapture(id, dir, thresholds, logger, pipeline, isoNow(), appVersion, recordingOk, recordingError)
        }
        if (!recordingOk) logger.note(mapOf("type" to "RECORDING_ERROR", "message" to recordingError))
        return mapOf("sessionId" to id, "recording" to recordingOk, "recordingError" to recordingError, "path" to dir.absolutePath)
    }

    /** Any thread except GL. Stops recording, finalises logs and writes the package documents. */
    fun stopCapture(reason: String): Map<String, Any?> {
        val cap = synchronized(this) {
            val c = capture ?: throw CaptureException("ERR_NOT_RUNNING", "No capture is running")
            if (c.stopping) throw CaptureException("ERR_NOT_RUNNING", "Capture is already stopping")
            c.stopping = true
            c
        }
        sessionLock.withLock {
            capture = null
            val s = session
            if (s != null) {
                try {
                    if (s.recordingStatus == RecordingStatus.OK) s.stopRecording()
                } catch (e: Exception) {
                    cap.recordingOk = false
                    cap.recordingError = "stopRecording: ${e.message}"
                    Log.e(TAG, "stopRecording failed", e)
                }
            }
        }
        val fin = cap.pipeline.finish()
        cap.logger.close()

        val video = File(cap.dir, VIDEO_FILE)
        if (cap.recordingOk && (!video.exists() || video.length() == 0L)) {
            cap.recordingOk = false
            cap.recordingError = "Recording file missing or empty"
        }
        val durationMs = fin.summary.durationMs
        val sessionInfo = ExportDocs.SessionInfo(
            sessionId = cap.sessionId,
            createdAtIso = cap.createdAtIso,
            durationMs = durationMs,
            poseCount = cap.logger.poseCount.get(),
            signalCount = cap.logger.signalCount.get(),
            imageSampleCount = cap.pipeline.imageSampleCount,
            interruptions = cap.interruptions,
            recordingMode = "ARCORE_RECORDING_API",
            recordingOk = cap.recordingOk,
            recordingError = cap.recordingError,
            videoFile = if (video.exists()) VIDEO_FILE else null,
            endReason = reason
        )
        val last = fin.lastSignal
        val trail = ExportDocs.TrailInfo(
            pathLengthM = last?.pathLengthM ?: 0f,
            keyframeCount = last?.keyframeCount ?: 0,
            maxDistanceFromStartM = last?.maxDistanceFromStartM ?: 0f,
            points = fin.trail
        )
        val coverage = CoverageState.POSE_TRAIL_ONLY
        val device = ExportDocs.DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE ?: "",
            Build.VERSION.SDK_INT, arcoreApkVersion())
        val summary = ExportDocs.summary(fin.summary, sessionInfo, trail, coverage)
        try {
            File(cap.dir, "coverage.json").writeText(JsonLite.write(ExportDocs.coverage(coverage, trail)))
            File(cap.dir, "summary.json").writeText(JsonLite.write(summary))
            File(cap.dir, "manifest.json").writeText(JsonLite.write(
                ExportDocs.manifest(sessionInfo, device, cap.camera, coverage, cap.appVersion, cap.thresholds.version)))
        } catch (e: Exception) {
            Log.e(TAG, "writing package documents failed", e)
        }
        @Suppress("UNCHECKED_CAST")
        val result = (jsSafe(summary) as Map<String, Any?>) + mapOf(
            "path" to cap.dir.absolutePath,
            "trail" to fin.trail.map { listOf(it[0].toDouble(), it[1].toDouble()) }
        )
        emit("sessionCompleted", result)
        return result
    }

    fun acceptSession(sessionId: String): Map<String, Any?> = exporter.accept(sessionId, isoNow())

    fun discardSession(sessionId: String): Boolean {
        if (capture?.sessionId == sessionId) throw CaptureException("ERR_BUSY", "This capture is still running")
        return exporter.discard(sessionId)
    }

    fun sharePackage(activity: Activity, sessionId: String): Boolean = exporter.share(activity, sessionId)

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    companion object {
        private const val TAG = "GuidedCapture"
        const val VIDEO_FILE = "session.mp4"
        /** Cap on the recorded CPU image size; 720p until Rysera states its preferred resolution. */
        const val MAX_CPU_IMAGE_PIXELS = 1280 * 720

        @Volatile private var instance: CaptureController? = null

        fun get(context: Context): CaptureController =
            instance ?: synchronized(this) {
                instance ?: CaptureController(context.applicationContext).also { instance = it }
            }
    }
}
