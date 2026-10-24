// SPDX-License-Identifier: Apache-2.0
// Accepted-session packaging, sharing and deletion (TDD v1.0 sections 15 and 16), separated from
// capture control. Session identifiers come from JavaScript and are treated as untrusted.
package lk.transitionx.capture.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import lk.transitionx.capture.export.PackageIntegrity
import lk.transitionx.capture.replay.JsonLite

class PackageExporter(private val context: Context, private val baseDir: () -> File) {

    private fun sessionsRoot() = File(baseDir(), "sessions")
    private fun exportsRoot() = File(baseDir(), "exports")

    fun sessionDirFor(sessionId: String): File {
        if (!ID.matches(sessionId)) throw CaptureException("ERR_BAD_ID", "Invalid session id")
        val root = sessionsRoot().canonicalFile
        val dir = File(root, "scan_$sessionId").canonicalFile
        // Defence in depth against path traversal: the resolved path must stay inside sessions/.
        if (dir.parentFile != root) throw CaptureException("ERR_BAD_ID", "Invalid session id")
        return dir
    }

    fun newSessionDir(sessionId: String): File {
        val dir = sessionDirFor(sessionId)
        if (!dir.mkdirs()) throw CaptureException("ERR_STORAGE", "Could not create the session folder")
        return dir
    }

    private fun existingSessionDir(sessionId: String): File {
        val dir = sessionDirFor(sessionId)
        if (!dir.isDirectory) throw CaptureException("ERR_NOT_FOUND", "Session not found")
        return dir
    }

    private fun zipFile(sessionId: String) = File(exportsRoot(), "scan_$sessionId.zip")

    /** Background thread. Zips the package with SHA-256 checksums; no upload endpoint exists yet. */
    fun accept(sessionId: String, acceptedAtIso: String): Map<String, Any?> {
        val dir = existingSessionDir(sessionId)
        File(dir, "status.json").writeText(JsonLite.write(mapOf(
            "accepted" to true, "acceptedAt" to acceptedAtIso, "transfer" to "MANUAL")))
        exportsRoot().mkdirs()
        val result = PackageIntegrity.zipWithChecksums(dir, zipFile(sessionId), "scan_$sessionId")
        return mapOf(
            "sessionId" to sessionId,
            "packagePath" to dir.absolutePath,
            "zipPath" to result.zip.absolutePath,
            "zipBytes" to result.bytes.toDouble(),
            "files" to result.digests.size + 1,
            "transfer" to "MANUAL"
        )
    }

    /** Deletes the session folder and its zip (re-record, or "delete from phone" after sharing). */
    fun discard(sessionId: String): Boolean {
        val dir = sessionDirFor(sessionId)
        zipFile(sessionId).delete()
        File(exportsRoot(), "scan_$sessionId.zip.part").delete()
        return !dir.exists() || dir.deleteRecursively()
    }

    /** Main thread. Only the zip is exposed, through a read-only, per-intent content:// grant. */
    fun share(activity: Activity, sessionId: String): Boolean {
        sessionDirFor(sessionId) // validates the id
        val zip = zipFile(sessionId)
        if (!zip.isFile) throw CaptureException("ERR_NOT_FOUND", "Accept the capture before sharing it")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".guidedcapture.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Guided capture scan_$sessionId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Send capture package"))
        return true
    }

    companion object {
        private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
