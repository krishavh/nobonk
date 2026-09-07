package ai.genwhy.nobonk.data

import java.io.File
import java.io.RandomAccessFile

/** Length-prefixed ciphertext only. Caller serializes access and provides AEAD. */
internal object FramedLog {
    private const val MAX_RECORD = 1_000_000
    fun append(file: File, record: ByteArray) {
        require(record.isNotEmpty() && record.size <= MAX_RECORD)
        RandomAccessFile(file, "rw").use { out ->
            val start = out.length()
            out.seek(start)
            try { out.writeInt(record.size); out.write(record) }
            catch (e: Exception) { out.setLength(start); throw e }
        }
    }

    /** A killed write must not hide every event appended after it on the next launch. */
    fun readAndRepair(file: File): List<ByteArray> {
        if (!file.exists()) return emptyList()
        return RandomAccessFile(file, "rw").use { input ->
            val records = mutableListOf<ByteArray>()
            var goodEnd = 0L
            while (input.filePointer < input.length()) {
                if (input.length() - input.filePointer < 4) break
                val size = input.readInt()
                if (size <= 0 || size > MAX_RECORD || input.length() - input.filePointer < size) break
                val record = ByteArray(size)
                input.readFully(record)
                records.add(record)
                goodEnd = input.filePointer
            }
            if (goodEnd < input.length()) input.setLength(goodEnd)
            records
        }
    }
}
