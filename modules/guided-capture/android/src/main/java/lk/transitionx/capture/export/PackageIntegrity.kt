// SPDX-License-Identifier: Apache-2.0
// export: integrity of the package handed to Rysera. SHA-256 per file in `sha256sum` format, so
// the receiver can run `sha256sum -c checksums.sha256` and detect truncated or altered files.
// Pure JVM (java.security, java.util.zip); no Android types.
package lk.transitionx.capture.export

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PackageIntegrity {
    const val CHECKSUM_FILE = "checksums.sha256"

    /** Files that are already compressed gain nothing from deflate; storing them is much faster. */
    private val PRECOMPRESSED = setOf("mp4", "zip", "jpg", "png")

    private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = FileInputStream(file).use { sha256Hex(it) }

    /** `<hex>  <name>` lines, sorted by name, newline-terminated (GNU coreutils format). */
    fun checksumText(digests: Map<String, String>): String =
        digests.entries.sortedBy { it.key }.joinToString("") { "${it.value}  ${it.key}\n" }

    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val hex = line.substring(0, 64)
            require(line.length > 66 && line[64] == ' ' && line[65] == ' ') { "bad checksum line" }
            line.substring(66) to hex
        }

    data class ZipResult(val zip: File, val bytes: Long, val digests: Map<String, String>)

    /**
     * Writes [dir]'s regular files into [zip] under `<rootName>/`, hashing each file in the same
     * pass, then adds the checksum file (also written into [dir]). Only plain file names are
     * accepted, so nothing outside the session directory can enter the archive.
     */
    fun zipWithChecksums(dir: File, zip: File, rootName: String): ZipResult {
        require(SAFE_NAME.matches(rootName)) { "bad archive root" }
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name != CHECKSUM_FILE && SAFE_NAME.matches(it.name) }
            .sortedBy { it.name }
        val digests = LinkedHashMap<String, String>()
        val tmp = File(zip.parentFile, zip.name + ".part")
        ZipOutputStream(FileOutputStream(tmp)).use { out ->
            val buf = ByteArray(256 * 1024)
            for (f in files) {
                out.setLevel(if (f.extension.lowercase() in PRECOMPRESSED) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                out.putNextEntry(ZipEntry("$rootName/${f.name}").apply { time = f.lastModified() })
                val md = MessageDigest.getInstance("SHA-256")
                FileInputStream(f).use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                out.closeEntry()
                digests[f.name] = md.digest().joinToString("") { "%02x".format(it) }
            }
            val text = checksumText(digests)
            File(dir, CHECKSUM_FILE).writeText(text)
            out.setLevel(Deflater.DEFAULT_COMPRESSION)
            out.putNextEntry(ZipEntry("$rootName/$CHECKSUM_FILE"))
            out.write(text.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        // Atomic replace: a half-written zip is never offered for sharing.
        if (zip.exists()) zip.delete()
        check(tmp.renameTo(zip)) { "could not finalise ${zip.name}" }
        return ZipResult(zip, zip.length(), digests)
    }
}
