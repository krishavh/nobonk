package ai.genwhy.nobonk.ml

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded-heap extraction of bundled models. Files are content-addressed, not trusted by name. */
internal class ModelFileCache(private val directory: File) {
    data class Model(val file: File, val sha256: String)
    private data class Identity(val hash: String, val size: Long)

    /**
     * Stream/hash the bundled asset and validate a cache hit without rewriting the model.
     * On a miss, extract with a second bounded pass and verify its identity before atomic
     * installation. The JVM lock prevents overlapping file-lock exceptions; the file lock
     * also serializes processes. Keep immutable files that an existing ORT session may use.
     * Lock acquisition is serialized; cancellation is checked immediately after acquisition
     * and between every I/O chunk (not asynchronously while another initializer owns it).
     */
    fun load(openAsset: () -> InputStream, checkActive: () -> Unit = {}): Model = synchronized(processLock) {
        checkActive()
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private model cache" }
        RandomAccessFile(File(directory, ".extract.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                checkActive()
                val buffer = ByteArray(BUFFER_BYTES)
                val bundled = openAsset().use { identify(it, buffer, checkActive) }
                check(bundled.size > 0) { "Bundled model is empty" }
                val destination = File(directory, "${bundled.hash}.onnx")
                val reusable = destination.isFile && destination.length() == bundled.size &&
                    destination.inputStream().use { identify(it, buffer, checkActive) } == bundled
                checkActive()
                if (!reusable) {
                    val temporary = File.createTempFile("model-", ".tmp", directory)
                    try {
                        val copied = openAsset().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val identity = identify(input, buffer, checkActive, output)
                                checkActive()
                                check(identity == bundled) { "Bundled model changed during extraction" }
                                output.fd.sync()
                                identity
                            }
                        }
                        check(copied == bundled)
                        checkActive()
                        // Same directory/filesystem. Unsupported atomic replacement fails safely;
                        // a partial or different model is never exposed as a verified destination.
                        Files.move(temporary.toPath(), destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                }
                Model(destination, bundled.hash)
            }
        }
    }

    private fun identify(input: InputStream, buffer: ByteArray, checkActive: () -> Unit,
                         output: FileOutputStream? = null): Identity {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            size += count
        }
        return Identity(digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
        private val processLock = Any()
    }
}
