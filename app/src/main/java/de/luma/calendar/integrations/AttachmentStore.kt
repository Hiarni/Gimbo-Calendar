package de.luma.calendar.integrations

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.EventAttachment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.UUID

class AttachmentStore(private val context: Context, private val database: CalendarDatabase = CalendarDatabase.get(context)) {
    val directory = File(context.filesDir, "attachments").apply { mkdirs() }
    suspend fun add(eventId: Long, uri: Uri): EventAttachment = mutex.withLock {
        require(uri.scheme == "content") { "Bitte eine Datei über die Dateiauswahl öffnen." }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase(java.util.Locale.ROOT) ?: error("Nicht unterstützter Dateityp.")
        require(mime in TYPES) { "Nur Bilder, PDF und Textdateien sind erlaubt." }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty().take(200).ifBlank { "Attachment" }
        val input = resolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")
        val bytes = input.use { bounded(it, MAX_FILE) }
        val value = EventAttachment(UUID.randomUUID().toString(), eventId, name, mime, bytes.size.toLong())
        saveCopies(listOf(value to bytes))
        value
    }
    suspend fun createFromShare(event: de.luma.calendar.model.Event, uri: Uri): Long = mutex.withLock {
        require(event.id == 0L && uri.scheme == "content")
        val mime = context.contentResolver.getType(uri)?.lowercase(java.util.Locale.ROOT) ?: error("Nicht unterstützter Dateityp."); require(mime in TYPES) { "Nur Bilder, PDF und Textdateien sind erlaubt." }
        val bytes = (context.contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")).use { bounded(it, MAX_FILE) }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().take(200).ifBlank { "Attachment" }
        val fileId = UUID.randomUUID().toString()
        try { database.withTransaction {
            val id = de.luma.calendar.data.EventRepository(database).save(event)
            saveCopies(listOf(EventAttachment(fileId, id, name, mime, bytes.size.toLong()) to bytes)); id
        } } catch (error: Exception) { file(fileId).delete(); throw error }
    }
    private suspend fun saveCopies(values: List<Pair<EventAttachment, ByteArray>>) {
        val created = mutableListOf<File>()
        try { database.withTransaction {
            val existing = database.integrations().attachments()
            require(existing.sumOf { it.bytes } + values.sumOf { it.second.size.toLong() } <= MAX_TOTAL) { "Anhänge: insgesamt höchstens 100 MB." }
            require((existing + values.map { it.first }).groupBy { it.eventId }.values.all { it.size <= 20 }) { "Maximal 20 Anhänge pro Termin." }
            values.forEach { (value, bytes) ->
                require(database.events().find(value.eventId) != null) { "Dieser Termin wurde bereits entfernt." }
                require(value.fileId.matches(ID) && value.mime in TYPES && value.bytes == bytes.size.toLong() && bytes.size <= MAX_FILE && value.name.isNotBlank() && value.name.length <= 200)
                val file = file(value.fileId); check(!file.exists()); created += file
                file.outputStream().use { it.write(bytes) }
                database.integrations().attach(value)
            }
        } } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }
    suspend fun restore(archive: de.luma.calendar.data.AttachmentArchive.Read): Int = mutex.withLock {
        val created = mutableListOf<EventAttachment>()
        try { database.withTransaction {
            de.luma.calendar.data.CalendarRepository(database).import(archive.backup, onEvents = { eventIds ->
                val values = archive.attachments.map { (a, bytes) -> a.copy(fileId = UUID.randomUUID().toString(), eventId = eventIds[a.eventId] ?: error("Ungültiger Anhang.")) to bytes }
                created += values.map { it.first }; saveCopies(values)
            })
        } } catch (error: Exception) { created.forEach { file(it.fileId).delete() }; throw error }
    }
    suspend fun remove(id: String) = mutex.withLock { database.integrations().removeAttachment(id); file(id).delete(); Unit }
    suspend fun collectGarbage() = mutex.withLock {
        val active = database.integrations().attachments().map { it.fileId }.toSet()
        directory.listFiles().orEmpty().filter { it.name.matches(ID) && it.name !in active }.forEach { it.delete() }
    }
    fun file(id: String): File { require(id.matches(ID)); return File(directory, id) }
    fun uri(id: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file(id))
    companion object {
        private val mutex = Mutex()
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "application/pdf", "text/plain")
        const val MAX_FILE = 10 * 1024 * 1024
        const val MAX_TOTAL = 100L * 1024 * 1024
        fun bounded(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= limit) { "Die Datei ist zu groß." }; out.write(buffer, 0, count) }
            return out.toByteArray()
        }
    }
}
