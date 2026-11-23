package de.luma.calendar.data

import android.content.Context
import androidx.room.withTransaction
import de.luma.calendar.integrations.AttachmentStore
import de.luma.calendar.model.*
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

object AttachmentArchive {
    data class Read(val backup: CalendarBackup, val attachments: List<Pair<EventAttachment, ByteArray>>, val travel: List<TravelPlan>)
    suspend fun write(context: Context, output: OutputStream, database: CalendarDatabase = CalendarDatabase.get(context)) {
        val db = database; val store = AttachmentStore(context, db)

        val (json, metadata) = db.withTransaction {
            val events = db.events().all(); val ids = events.mapIndexed { i, e -> e.id to i + 1L }.toMap()
            val attachments = db.integrations().attachments().map { it.copy(eventId = ids[it.eventId] ?: error("Ungültiger Anhang.")) }
            CalendarFiles.backup(events, db.calendars().all(), db.exceptions().all(), db.tasks().all(), db.templates().all(), db.integrations().travel(), db.categories().all(), db.notes().all()) to attachments
        }
        require(json.toByteArray().size <= CalendarFiles.MAX_BYTES)
        val attachments = metadata
        require(attachments.size <= 10000 && attachments.sumOf { it.bytes } <= AttachmentStore.MAX_TOTAL)
        val hashes = mutableMapOf<String, String>()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("calendar.json", json.toByteArray(Charsets.UTF_8))
            attachments.forEach { a ->
                val bytes = store.file(a.fileId).inputStream().use { AttachmentStore.bounded(it, AttachmentStore.MAX_FILE) }
                require(bytes.size.toLong() == a.bytes); hashes[a.fileId] = hash(bytes)
                entry("attachments/${a.fileId}", bytes)
            }
            val manifest = JSONObject().put("format", "luma-calendar-archive").put("version", 1)
                .put("attachments", JSONArray().also { array -> attachments.forEach { a -> array.put(JSONObject().put("id", a.fileId).put("eventId", a.eventId).put("name", a.name).put("mime", a.mime).put("bytes", a.bytes).put("sha256", hashes[a.fileId])) } })
            val bytes = manifest.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= HolidayStore.MAX_BYTES)
            entry("manifest.json", bytes)
        }
    }
    fun read(input: InputStream): Read {
        val files = mutableMapOf<String, ByteArray>(); var total = 0L
        ZipInputStream(input).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(!entry.isDirectory && (name in listOf("calendar.json", "manifest.json") || name.startsWith("attachments/") && name.removePrefix("attachments/").matches(AttachmentStore.ID))) { "Keine unterstützte Gimbo-ZIP-Sicherung." }
            require(name !in files && files.size < 10002) { "Keine unterstützte Gimbo-ZIP-Sicherung." }
            val limit = if (name == "manifest.json") HolidayStore.MAX_BYTES else AttachmentStore.MAX_FILE
            val bytes = AttachmentStore.bounded(zip, limit); total += bytes.size
            require(total <= AttachmentStore.MAX_TOTAL + CalendarFiles.MAX_BYTES + HolidayStore.MAX_BYTES) { "Die Datei ist zu groß." }
            files[name] = bytes; zip.closeEntry()
        } }
        fun text(name: String) = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(files[name] ?: error("Keine unterstützte Gimbo-ZIP-Sicherung."))).toString()
        val backup = CalendarFiles.readCalendarBackup(text("calendar.json"))
        val manifest = JSONObject(text("manifest.json")); require(manifest.getString("format") == "luma-calendar-archive" && manifest.getInt("version") == 1)
        val eventIds = backup.events.map { it.id }.toSet()
        val array = manifest.getJSONArray("attachments"); require(array.length() <= 10000)
        val values = (0 until array.length()).map { i -> val a = array.getJSONObject(i)
            val value = EventAttachment(a.getString("id"), a.getLong("eventId"), a.getString("name"), a.getString("mime"), a.getLong("bytes"))
            require(value.fileId.matches(AttachmentStore.ID) && value.eventId in eventIds && value.mime in AttachmentStore.TYPES && value.name.isNotBlank() && value.name.length <= 200)
            val bytes = files["attachments/${value.fileId}"] ?: error("Ungültiger Anhang.")
            require(value.bytes == bytes.size.toLong() && a.getString("sha256") == hash(bytes)) { "Ungültiger Anhang." }
            value to bytes
        }
        require(values.map { it.first.fileId }.distinct().size == values.size && values.groupBy { it.first.eventId }.values.all { it.size <= 20 } && values.sumOf { it.first.bytes } <= AttachmentStore.MAX_TOTAL)
        require(files.keys == values.map { "attachments/${it.first.fileId}" }.toSet() + setOf("calendar.json", "manifest.json"))
        val plans = backup.travel
        return Read(backup, values, plans)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
