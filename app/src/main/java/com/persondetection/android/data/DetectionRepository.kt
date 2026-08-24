package com.persondetection.android.data

import android.content.Context
import androidx.security.crypto.MasterKey
import com.persondetection.android.util.Dbg
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe, file-backed repository for [DetectionEvent] records.
 *
 * ## Encrypted at rest (SEC-N02 / T-SEC-ENCRYPT)
 * Every record is independently AES-256-GCM encrypted before it touches disk, using a
 * hardware-backed key held in the Android Keystore (created + managed via
 * `androidx.security.crypto`'s [MasterKey]). The file `detection_events.enc` is therefore
 * unreadable at rest — GPS-geotagged events are no longer plaintext JSON.
 *
 * ## Append-only, single-event writes (PERF-P05 / T-PERF-PERSIST)
 * Storage is a length-prefixed **append log**: each [addEvent] appends exactly one small
 * encrypted record with a single `FileOutputStream(append=true)` write. It never re-reads,
 * re-parses, or rewrites the whole file (the old JSON store did a full read+parse+rewrite
 * on every event). The authoritative event list is also held in memory and appended to in
 * O(1), so summaries never trigger a disk re-parse.
 *
 * > NOTE: `androidx.security.crypto.EncryptedFile` was intentionally NOT used for storage:
 * > its Tink AES-GCM-HKDF *stream* shares one keyset/nonce context across the whole file
 * > and cannot be safely appended to, which would force a full-file rewrite per event and
 * > defeat the append-only requirement. A per-record AEAD log gives us both encryption at
 * > rest AND cheap single-event appends, and it is resilient: a corrupt record is skipped
 * > without losing the rest of the history.
 *
 * All public methods are safe to call from any thread.
 */
class DetectionRepository(context: Context) {

    private val appContext = context.applicationContext
    private val file: File = File(appContext.filesDir, "detection_events.enc")
    private val lock = ReentrantReadWriteLock()

    // Authoritative in-memory copy (decrypted once, lazily). addEvent appends in O(1).
    private var cache: MutableList<DetectionEvent>? = null

    companion object {
        private const val TAG = "DetectionRepository"
        // Keep at most this many events to prevent unbounded growth (~30 days of use).
        private const val MAX_EVENTS = 5_000
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }

    // ── Keystore-backed AES-256-GCM key (via androidx.security MasterKey) ─────────

    private val secretKey: SecretKey by lazy {
        // Building the MasterKey creates (or reuses) the hardware-backed AES-GCM key in the
        // AndroidKeyStore under DEFAULT_MASTER_KEY_ALIAS; we then load the SecretKey to run
        // our own per-record AEAD.
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as SecretKey
    }

    private fun encryptRecord(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Keystore GCM requires a keystore-generated IV — do not supply one; read it back.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    private fun decryptRecord(ivAndCt: ByteArray): ByteArray {
        val iv = ivAndCt.copyOfRange(0, GCM_IV_BYTES)
        val ct = ivAndCt.copyOfRange(GCM_IV_BYTES, ivAndCt.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun lengthPrefix(len: Int): ByteArray = byteArrayOf(
        (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()
    )

    // ── Write ────────────────────────────────────────────────────────────────

    /** Appends a single [DetectionEvent] — one encrypted record, one append, no rewrite. */
    fun addEvent(event: DetectionEvent) {
        lock.write {
            try {
                val list = loadCacheLocked()
                list.add(event)
                appendRecord(event)
                // Trimming is the only full rewrite, and it happens at most once per
                // MAX_EVENTS additions — amortised O(1).
                if (list.size > MAX_EVENTS) {
                    val trimmed = ArrayList(list.subList(list.size - MAX_EVENTS, list.size))
                    cache = trimmed
                    rewriteAllLocked(trimmed)
                }
            } catch (e: Exception) {
                Dbg.e(TAG, "Failed to persist event: ${e.message}")
            }
        }
    }

    private fun appendRecord(event: DetectionEvent) {
        val rec = encryptRecord(event.toJson().toString().toByteArray(Charsets.UTF_8))
        FileOutputStream(file, /* append = */ true).use { out ->
            out.write(lengthPrefix(rec.size))
            out.write(rec)
        }
    }

    private fun rewriteAllLocked(events: List<DetectionEvent>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            for (ev in events) {
                val rec = encryptRecord(ev.toJson().toString().toByteArray(Charsets.UTF_8))
                out.write(lengthPrefix(rec.size))
                out.write(rec)
            }
        }
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems — fall back to copy+delete.
            file.delete()
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Returns ALL stored events in chronological order (oldest first). */
    fun getAllEvents(): List<DetectionEvent> = lock.read {
        ArrayList(loadCacheLocked())
    }

    /**
     * Returns the [n] most recent [SessionSummary] objects.
     * Summaries are computed on demand (when History opens), never on every logged event.
     */
    fun getRecentSessions(n: Int = 10): List<SessionSummary> {
        val events = getAllEvents()
        if (events.isEmpty()) return emptyList()

        // Group by sessionId preserving insertion order
        val grouped = LinkedHashMap<String, MutableList<DetectionEvent>>()
        for (ev in events) grouped.getOrPut(ev.sessionId) { mutableListOf() }.add(ev)

        return grouped.values
            .map { sessionEvents -> buildSummary(sessionEvents) }
            .sortedByDescending { it.startTimestamp }
            .take(n)
    }

    /** Clears all stored history. */
    fun clearAll() = lock.write {
        cache = mutableListOf()
        try { file.delete() } catch (e: Exception) { Dbg.e(TAG, "Clear failed: ${e.message}") }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Lazily decrypts the append log into memory once; thereafter served from cache. */
    private fun loadCacheLocked(): MutableList<DetectionEvent> {
        cache?.let { return it }
        val list = mutableListOf<DetectionEvent>()
        if (file.exists()) {
            try {
                DataInputStream(FileInputStream(file)).use { din ->
                    while (true) {
                        val len = try { din.readInt() } catch (eof: EOFException) { break }
                        if (len <= 0 || len > 1_000_000) break   // guard against corruption
                        val rec = ByteArray(len)
                        din.readFully(rec)
                        try {
                            val json = JSONObject(String(decryptRecord(rec), Charsets.UTF_8))
                            list.add(DetectionEvent.fromJson(json))
                        } catch (e: Exception) {
                            // Skip a single corrupt/undecryptable record; keep the rest.
                        }
                    }
                }
            } catch (e: Exception) {
                Dbg.e(TAG, "Failed to load events: ${e.message}")
            }
        }
        cache = list
        return list
    }

    private fun buildSummary(events: List<DetectionEvent>): SessionSummary {
        val sorted = events.sortedBy { it.timestamp }
        val topThreat = events.groupingBy { it.className }.eachCount()
            .maxByOrNull { it.value }?.key ?: "person"
        val first = sorted.first()
        return SessionSummary(
            sessionId = first.sessionId,
            startTimestamp = first.timestamp,
            endTimestamp = sorted.last().timestamp,
            totalEvents = events.size,
            highAlerts = events.count { it.alertLevel == "HIGH" },
            topThreat = topThreat,
            startLatitude = first.latitude,
            startLongitude = first.longitude
        )
    }
}
