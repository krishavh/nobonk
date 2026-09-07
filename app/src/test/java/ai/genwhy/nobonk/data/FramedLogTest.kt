package ai.genwhy.nobonk.data

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class FramedLogTest {
    private fun withFile(test: (File) -> Unit) {
        val file = File.createTempFile("nobonk-log", ".enc")
        try { test(file) } finally { file.delete() }
    }
    @Test fun truncatedHeaderAndBodyDoNotHideLaterAppends() = withFile { file ->
        for (partial in listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 5, 42))) {
            file.writeBytes(byteArrayOf())
            FramedLog.append(file, byteArrayOf(1, 2, 3))
            file.appendBytes(partial)
            assertEquals(1, FramedLog.readAndRepair(file).size)
            FramedLog.append(file, byteArrayOf(4, 5))
            val records = FramedLog.readAndRepair(file)
            assertEquals(2, records.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), records[0])
            assertArrayEquals(byteArrayOf(4, 5), records[1])
        }
    }
    @Test fun impossibleLengthIsRemovedWithoutAllocatingIt() = withFile { file ->
        FramedLog.append(file, byteArrayOf(9))
        RandomAccessFile(file, "rw").use { it.seek(it.length()); it.writeInt(Int.MAX_VALUE) }
        assertEquals(1, FramedLog.readAndRepair(file).size)
        assertEquals(5, file.length())
    }
    @Test fun ciphertextBytesAndRecordBoundariesSurviveRoundTrip() = withFile { file ->
        val records = listOf(ByteArray(256) { it.toByte() }, byteArrayOf(0, 0, 0, 0))
        records.forEach { FramedLog.append(file, it) }
        val restored = FramedLog.readAndRepair(file)
        records.zip(restored).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(records.size, restored.size)
    }
}
