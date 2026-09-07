package ai.genwhy.nobonk.data

import android.content.Context
import androidx.security.crypto.MasterKey
import ai.genwhy.nobonk.util.Dbg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.write

/**
 * Thread-safe, file-backed repository for [DetectionEvent] records.
 *
 * ## Encrypted at rest (SEC-N02 / T-SEC-ENCRYPT)
 * Every record is independently AES-256-GCM encrypted before it touches disk, using a
 * Keystore-backed key held in the Android Keystore (created + managed via
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
    // Pre-round-2 plaintext store. It held GPS-geotagged events in the clear — the exact
    // thing SEC-N02 / T-SEC-ENCRYPT set out to fix. On upgrade we migrate its records into
    // the encrypted log and then remove it after a successful migration. Flash storage cannot guarantee secure erasure.
    private val legacyFile: File = File(appContext.filesDir, "detection_events.json")
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
        // Building the MasterKey creates (or reuses) the Keystore-managed AES-GCM key in the
        // AndroidKeyStore under DEFAULT_MASTER_KEY_ALIAS; we then load the SecretKey to run
        // our own per-record AEAD.
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as SecretKey
    }

    init { migrateLegacyPlaintext() } // all property delegates must exist before migration uses them

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
    fun addEvent(event: DetectionEvent): Boolean {
        return lock.write {
            try {
                val list = loadCacheLocked()
                appendRecord(event)
                list.add(event)
                // Trimming is the only full rewrite, and it happens at most once per
                // 500 additions after reaching the cap — amortised O(1).
                if (list.size > MAX_EVENTS) {
                    val trimmed = ArrayList(list.subList(list.size - (MAX_EVENTS - 500), list.size))
                    rewriteAllLocked(trimmed)
                    cache = trimmed
                }
                true
            } catch (e: Exception) {
                Dbg.e(TAG, "Failed to persist event: ${e.message}")
                false
            }
        }
    }

    private fun appendRecord(event: DetectionEvent) {
        val rec = encryptRecord(event.toJson().toString().toByteArray(Charsets.UTF_8))
        FramedLog.append(file, rec)
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
        // Same-directory replacement is atomic. Never delete the original on a failed rename.
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not replace history")
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Returns ALL stored events in chronological order (oldest first). */
    fun getAllEvents(): List<DetectionEvent> = lock.write {
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
        if (file.exists() && !file.delete()) throw java.io.IOException("History deletion failed")
        val tmp = File(file.parentFile, "${file.name}.tmp")
        if (tmp.exists() && !tmp.delete()) throw java.io.IOException("Temporary history deletion failed")
        cache = mutableListOf()
    }

    // ── Legacy plaintext migration (SEC-N02 upgrade path) ──────────────────────

    /** Encrypt the legacy store before deleting it; failures preserve the source for retry. */
    private fun migrateLegacyPlaintext() {
        if (!legacyFile.exists()) return
        lock.write {
            if (!legacyFile.exists()) return@write
            val list = loadCacheLocked().toMutableList()
            val known = list.map { it.id }.toMutableSet()
            val text = legacyFile.readText().trim()
            if (text.isNotEmpty()) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val event = DetectionEvent.fromJson(arr.getJSONObject(i))
                    if (known.add(event.id)) list.add(event)
                }
            }
            // Migration is transactional: retain the private legacy source if encryption
            // or replacement fails, and deduplicate by id when retried.
            val retained = list.takeLast(MAX_EVENTS)
            rewriteAllLocked(retained)
            cache = retained.toMutableList()
            secureDeleteLegacy()
        }
    }

    /** Best-effort overwrite and deletion; flash wear levelling prevents an erase guarantee. */
    private fun secureDeleteLegacy() {
        try {
            if (!legacyFile.exists()) return
            val len = legacyFile.length()
            if (len > 0) {
                FileOutputStream(legacyFile).use { out ->
                    val zeros = ByteArray(4096)
                    var written = 0L
                    while (written < len) {
                        val n = minOf(zeros.size.toLong(), len - written).toInt()
                        out.write(zeros, 0, n)
                        written += n
                    }
                    out.fd.sync()
                }
            }
            legacyFile.delete()
        } catch (e: Exception) {
            Dbg.e(TAG, "Legacy secure-delete failed: ${e.message}")
            try { legacyFile.delete() } catch (_: Exception) {}
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Lazily decrypts the append log into memory once; thereafter served from cache. */
    private fun loadCacheLocked(): MutableList<DetectionEvent> {
        cache?.let { return it }
        val list = mutableListOf<DetectionEvent>()
        if (file.exists()) {
            for (rec in FramedLog.readAndRepair(file)) {
                try {
                    val json = JSONObject(String(decryptRecord(rec), Charsets.UTF_8))
                    list.add(DetectionEvent.fromJson(json))
                } catch (e: Exception) {
                    Dbg.w(TAG, "Skipping an unreadable history record")
                }
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
