package de.luma.calendar.model

import androidx.room.*
import org.json.*
import java.util.Locale
import java.util.UUID

enum class NoteKind { TEXT, CHECKLIST }
data class NoteItem(val id: String = UUID.randomUUID().toString(), val text: String, val checked: Boolean = false) : java.io.Serializable

@Entity(tableName = "notes", foreignKeys = [ForeignKey(entity = UserCalendar::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("calendarId")])
@TypeConverters(NotePayloadConverter::class)
data class CalendarNote(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String = "", val body: String = "", val kind: NoteKind = NoteKind.TEXT,
    val items: List<NoteItem> = emptyList(), val labels: List<String> = emptyList(), val color: Long = 0xFFB9A7FF, val pinned: Boolean = false, val archived: Boolean = false,
    val calendarId: Long = 1, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt) : java.io.Serializable {
    fun checked(): CalendarNote {
        require(id >= 0 && calendarId > 0 && createdAt >= 0 && updatedAt >= createdAt) { "Bitte Eingaben prüfen." }
        require(title.isNotBlank() || body.isNotBlank() || items.any { it.text.isNotBlank() }) { "Bitte Notizinhalt eingeben." }
        require(title.length <= 200 && body.length <= 20000) { "Notiz ist zu groß." }
        require(color in 0xFF000000L..0xFFFFFFFFL) { "Ungültige Notizfarbe." }
        require(items.size <= 200 && items.map { it.id }.distinct().size == items.size && items.all { it.id.length in 1..64 && it.text.isNotBlank() && it.text.length <= 500 } && (kind != NoteKind.TEXT || items.isEmpty())) { "Ungültige Checkliste." }
        require(labels.size <= 10 && labels.all { it.isNotBlank() && it.length <= 40 }) { "Ungültige Notizetiketten." }
        return copy(title = title.trim(), items = items.map { it.copy(text = it.text.trim()) }, labels = labels.map(String::trim).distinctBy { it.lowercase(Locale.ROOT) })
    }
    companion object { const val MAX_COUNT = 2000 }
}

class NotePayloadConverter {
    @TypeConverter fun items(value: List<NoteItem>): String = JSONArray().also { a -> value.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("checked", it.checked)) } }.toString()
    @TypeConverter fun readItems(value: String): List<NoteItem> = JSONArray(value).let { a -> require(a.length() <= 200) { "Ungültige Checkliste." }; (0 until a.length()).map { i -> val item = a.getJSONObject(i); NoteItem(item.getString("id"), item.getString("text"), item.getBoolean("checked")) } }
    @TypeConverter fun labels(value: List<String>): String = JSONArray(value).toString()
    @TypeConverter fun readLabels(value: String): List<String> = JSONArray(value).let { a -> require(a.length() <= 10) { "Ungültige Notizetiketten." }; (0 until a.length()).map(a::getString) }
}

object NoteCodec {
    fun json(note: CalendarNote): JSONObject = JSONObject().put("id", note.id).put("title", note.title).put("body", note.body).put("kind", note.kind.name)
        .put("items", JSONArray(NotePayloadConverter().items(note.items))).put("labels", JSONArray(note.labels)).put("color", note.color)
        .put("pinned", note.pinned).put("archived", note.archived).put("calendarId", note.calendarId).put("createdAt", note.createdAt).put("updatedAt", note.updatedAt)
    fun read(note: JSONObject): CalendarNote = CalendarNote(note.getLong("id"), note.getString("title"), note.getString("body"), NoteKind.valueOf(note.getString("kind")),
        NotePayloadConverter().readItems(note.getJSONArray("items").toString()), NotePayloadConverter().readLabels(note.getJSONArray("labels").toString()), note.getLong("color"),
        note.getBoolean("pinned"), note.getBoolean("archived"), note.getLong("calendarId"), note.getLong("createdAt"), note.getLong("updatedAt")).checked()
}
