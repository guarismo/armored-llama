package com.iguar.armoredllama.server

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Absolute path of the server executable to run + the dir holding its `.so` deps (for LD_LIBRARY_PATH). */
data class ExecTarget(val execPath: String, val libDir: String)

/**
 * Resolves which llama-server binary the service runs and installs downloaded updates.
 * Downloaded-active wins over the build-time bundled binary; a missing/invalid download falls back
 * to bundled. Takes plain [File] dirs (not a Context) so its logic is host-JVM testable; [chmod] is
 * injectable and defaults to android.system.Os.chmod on-device.
 */
class RuntimeBinaries(
    private val filesDir: File,
    private val bundledLibDir: File,
    private val chmod: (String, Int) -> Unit = { path, mode -> android.system.Os.chmod(path, mode) },
) {
    private val root = File(filesDir, "llama")
    private val activeFile = File(root, "active.txt")

    fun activeTag(): String {
        val tag = activeFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (tag.isBlank() || tag == BUNDLED_TAG) return BUNDLED_TAG
        return if (execFor(tag).exists()) tag else BUNDLED_TAG
    }

    fun activeExecutable(): ExecTarget {
        val tag = activeTag()
        if (tag == BUNDLED_TAG) {
            return ExecTarget(File(bundledLibDir, BUNDLED_EXEC).unixPath(), bundledLibDir.unixPath())
        }
        val dir = File(root, tag)
        return ExecTarget(File(dir, EXEC_NAME).unixPath(), dir.unixPath())
    }

    fun hasDownloadedActive(): Boolean = activeTag() != BUNDLED_TAG

    /** Remove all downloaded runtimes and cached update assets; subsequent launches use bundled. */
    fun resetToBundled() {
        root.deleteRecursively()
    }

    /** Extract [tarGz] into filesDir/llama/<tag>/, chmod the exec 0700, record active, prune others. */
    fun install(tag: String, tarGz: InputStream) {
        val dir = File(root, tag).apply { mkdirs() }
        GZIPInputStream(tarGz).use { gz ->
            for (entry in readTar(gz)) {
                val leaf = entry.name.substringAfterLast('/')
                if (leaf.isBlank()) continue
                val isExec = leaf == EXEC_NAME
                val isSo = leaf.endsWith(".so")
                if (!isExec && !isSo) continue
                File(dir, leaf).writeBytes(entry.bytes)
            }
        }
        chmod(File(dir, EXEC_NAME).unixPath(), 448) // 0700
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(tag)
        // Prune any other downloaded version dirs.
        root.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != tag) f.deleteRecursively()
        }
    }

    private fun execFor(tag: String) = File(File(root, tag), EXEC_NAME)

    /** [File.getPath] with `/` separators — device paths (and Android exec()) are always `/`-separated
     *  regardless of the host OS this test suite runs on. */
    private fun File.unixPath() = path.replace('\\', '/')

    companion object {
        const val BUNDLED_TAG = "b9775"                 // must match llamaRelease in build.gradle.kts
        const val BUNDLED_EXEC = "libllamaserver.so"    // exec name in nativeLibraryDir
        const val EXEC_NAME = "llama-server"            // exec name inside a release tarball
    }
}
