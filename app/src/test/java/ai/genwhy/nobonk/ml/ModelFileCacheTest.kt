package ai.genwhy.nobonk.ml

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelFileCacheTest {
    private fun withCache(block: (File, ModelFileCache) -> Unit) {
        val dir = Files.createTempDirectory("nobonk-model-test").toFile()
        try { block(dir, ModelFileCache(dir)) } finally { dir.deleteRecursively() }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertNoTemps(dir: File) = assertTrue(dir.listFiles()!!.none { it.extension == "tmp" })

    @Test fun preservesExactModelBytesAndHashUsedForProviderIdentity() = withCache { dir, cache ->
        val data = ByteArray(123_456) { (it * 31).toByte() }
        val result = cache.load({ ByteArrayInputStream(data) })
        assertEquals(hash(data), result.sha256)
        assertEquals("${result.sha256}.onnx", result.file.name)
        assertArrayEquals(data, result.file.readBytes())
        assertNoTemps(dir)
    }
    @Test fun reusableFileIsNotReplacedButCorruptionIsRepaired() = withCache { dir, cache ->
        val data = ByteArray(8192) { it.toByte() }
        val original = cache.load({ ByteArrayInputStream(data) })
        assertTrue(original.file.setLastModified(123_456_000L))
        var assetOpens = 0
        val reused = cache.load({ assetOpens++; ByteArrayInputStream(data) })
        assertEquals("Cache hits hash the asset once without an extraction pass", 1, assetOpens)
        assertEquals(123_456_000L, reused.file.lastModified())
        // Same-length corruption must also be detected, not just truncation.
        reused.file.writeBytes(ByteArray(data.size))
        val repaired = cache.load({ ByteArrayInputStream(data) })
        assertArrayEquals(data, repaired.file.readBytes())
        assertNoTemps(dir)
    }
    @Test fun assetChangesKeepBothImmutableModelVersions() = withCache { _, cache ->
        val first = cache.load({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val second = cache.load({ ByteArrayInputStream(byteArrayOf(4, 5, 6)) })
        assertNotEquals(first.file, second.file)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.file.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), second.file.readBytes())
    }
    @Test fun largeAssetNeverRequestsAModelSizedHeapBuffer() = withCache { dir, cache ->
        val size = 40L * 1024 * 1024
        val result = cache.load({ object : InputStream() {
            var remaining = size
            override fun read(): Int = error("Loader should read bounded chunks")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(buffer.size <= ModelFileCache.BUFFER_BYTES)
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                buffer.fill(7, offset, offset + count)
                remaining -= count
                return count
            }
        } })
        assertEquals(size, result.file.length())
        assertNoTemps(dir)
    }
    @Test fun cancellationDeletesPartialExtractionAndPreservesExistingFile() = withCache { dir, cache ->
        val previous = cache.load({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        var extraction = false
        var checks = 0
        var opens = 0
        try {
            cache.load({ extraction = ++opens == 2; ByteArrayInputStream(ByteArray(256 * 1024)) }) {
                if (extraction && ++checks == 3) throw CancellationException("Stopped")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertArrayEquals(byteArrayOf(1, 2, 3), previous.file.readBytes())
        assertNoTemps(dir)
        assertEquals(1, dir.listFiles()!!.count { it.extension == "onnx" })
    }
    @Test fun readFailureOrEmptyAssetCannotPublishAPartialModel() = withCache { dir, cache ->
        try {
            cache.load({ object : InputStream() { override fun read(): Int = throw IOException("Broken asset") } })
            fail("Read failure must propagate")
        } catch (_: IOException) { }
        try { cache.load({ ByteArrayInputStream(byteArrayOf()) }); fail("Empty model must fail") }
        catch (_: IllegalStateException) { }
        assertNoTemps(dir)
        assertTrue(dir.listFiles()!!.none { it.extension == "onnx" })
    }
    @Test fun assetChangingBetweenHashAndExtractionCannotPublishWrongIdentity() = withCache { dir, cache ->
        var opens = 0
        try {
            cache.load({ ByteArrayInputStream(if (++opens == 1) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6)) })
            fail("Extraction must match the original streamed identity")
        } catch (_: IllegalStateException) { }
        assertNoTemps(dir)
        assertTrue(dir.listFiles()!!.none { it.extension == "onnx" })
    }
    @Test fun concurrentInitializersShareOnlyACompleteVerifiedFile() = withCache { dir, _ ->
        val data = ByteArray(180_000) { (it * 7).toByte() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { Callable { ModelFileCache(dir).load({ ByteArrayInputStream(data) }) } }
            val results = executor.invokeAll(tasks).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.file }.toSet().size)
            results.forEach { assertEquals(hash(data), it.sha256); assertArrayEquals(data, it.file.readBytes()) }
            assertNoTemps(dir)
        } finally { executor.shutdownNow() }
    }
}
