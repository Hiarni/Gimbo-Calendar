package de.luma.calendar

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import de.luma.calendar.integrations.*
import de.luma.calendar.reminders.ReminderScheduler
import de.luma.calendar.widget.CalendarWidgets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class CalendarState(
    val categories: List<EventCategory> = emptyList(),
    val events: List<Event> = emptyList(),
    val templates: List<EventTemplate> = emptyList(),
    val tasks: List<CalendarTask> = emptyList(),
    val notes: List<CalendarNote> = emptyList(),
    val noteCompletion: Long = 0,
    val noteError: String? = null,
    val calendars: List<UserCalendar> = emptyList(),
    val exceptions: List<EventException> = emptyList(),
    val calendarCompletion: Long = 0,
    val calendarError: String? = null,
    val school: de.luma.calendar.integrations.SchoolSnapshot = de.luma.calendar.integrations.SchoolSnapshot(),
    val holidays: HolidaySnapshot = HolidaySnapshot(),
    val holidaysLoaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val eventError: String? = null,
    val deviceCalendars: List<de.luma.calendar.integrations.DeviceCalendar>? = null,
    val providerEditor: de.luma.calendar.integrations.ProviderEdit? = null,
    val needsWritePermission: Boolean = false,
    val pendingTasks: List<de.luma.calendar.integrations.TaskPreview>? = null,
    val sharedFile: Uri? = null,
    val pendingArchive: de.luma.calendar.data.AttachmentArchive.Read? = null,
    val attachments: List<EventAttachment> = emptyList(),
    val travel: List<TravelPlan> = emptyList(),
    val connectDeviceCalendar: Boolean = false,
    val deviceSources: DeviceSourceSnapshot = DeviceSourceSnapshot(),
    val sourcesLoading: Boolean = true,
    val pendingImport: CalendarBackup? = null,
    val editCompletion: Long = 0,
    val savedWithReminder: Boolean = false,
)
class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val database = CalendarDatabase.get(application)
    private val calendarRepository = CalendarRepository(database)
    private val eventRepository = EventRepository(database)
    private var providerRequest: Triple<de.luma.calendar.integrations.DeviceCalendar, Long?, Boolean>? = null
    private val schoolStore = de.luma.calendar.integrations.SchoolHolidays(application)
    private val holidayStore = HolidayStore(application)
    private val settingsStore = SettingsStore(application)
    private val connections = (application as CalendarApplication).deviceConnections
    private val sourceDate = MutableStateFlow(LocalDate.now())
    private val mutableState = MutableStateFlow(CalendarState(settings = settingsStore.read()))
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()
    private fun t(source: String, vararg arguments: Any) = AppLocale.wrap(getApplication()).text(source, *arguments)
    fun syncDeviceCalendar(calendar: DeviceCalendar) = operation {
        val result = SharedCalendars.requestSync(getApplication(), calendar)
        connections.refresh()
        mutableMessages.emit(t(when (result) { SyncRequest.REQUESTED -> "Anbieterabgleich angefordert"; SyncRequest.NO_ADAPTER -> "Für dieses Konto ist kein Kalenderabgleich eingerichtet. Android-Kontoeinstellungen prüfen."; SyncRequest.UNAVAILABLE -> "Kalender oder Freigabe nicht verfügbar." }))
    }
    fun refreshSettings() { mutableState.update { it.copy(settings = settingsStore.read()) } }
    init {
        viewModelScope.launch {
            combine(connections.revision, sourceDate) { revision, date -> revision to date }.collectLatest { (_, date) ->
                mutableState.update { it.copy(sourcesLoading = true) }
                val from = date.minusYears(1).coerceAtLeast(LocalDate.of(1900, 1, 1))
                val until = date.plusYears(1).coerceAtMost(LocalDate.of(2101, 1, 1))
                val snapshot = withContext(Dispatchers.IO) { DeviceCalendarSources.read(getApplication(), from, until) }
                mutableState.update { it.copy(deviceSources = snapshot, sourcesLoading = false) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { mutableState.update { it.copy(holidays = holidayStore.read(), school = schoolStore.read(mutableState.value.settings), holidaysLoaded = true) } }
        viewModelScope.launch {
            database.observeSnapshot().catch { mutableState.update { it.copy(loading = false, error = t("Termine konnten nicht geladen werden. Bitte die App erneut öffnen.")) } }
                .collect { (events, calendars, exceptions, tasks, templates, attachments, travel, categories, notes) -> mutableState.update { it.copy(notes = notes, categories = categories, events = events, calendars = calendars, exceptions = exceptions, tasks = tasks, templates = templates, attachments = attachments, travel = travel, loading = false, error = null) } }
        }
    }
    fun saveCategory(value: EventCategory) = operation { CategoryRepository(database).save(value) }
    fun removeCategory(id: Long) = operation { CategoryRepository(database).remove(id) }
    fun settings(value: AppSettings) {
        settingsStore.write(value)
        mutableState.update { it.copy(settings = value) }
        viewModelScope.launch(Dispatchers.IO) { val snapshot = schoolStore.read(value); if (mutableState.value.settings == value) mutableState.update { it.copy(school = snapshot) } }
        viewModelScope.launch(Dispatchers.IO) { CalendarWidgets.update(getApplication()) }
    }
    fun receiveSharedFile(uri: Uri) { if (uri.scheme == "content") mutableState.update { it.copy(sharedFile = uri) } }
    fun cancelSharedFile() { mutableState.update { it.copy(sharedFile = null) } }
    fun beginProviderEdit(calendar: de.luma.calendar.integrations.DeviceCalendar, eventId: Long? = null, remove: Boolean = false) {
        providerRequest = Triple(calendar, eventId, remove)
        if (de.luma.calendar.integrations.DeviceCalendarMutations.allowed(getApplication())) resumeProviderEdit(true)
        else mutableState.update { it.copy(needsWritePermission = true) }
    }
    fun resumeProviderEdit(allowed: Boolean) {
        mutableState.update { it.copy(needsWritePermission = false, eventError = null) }
        val request = providerRequest; providerRequest = null
        if (allowed && request != null) operation {
            val record = de.luma.calendar.integrations.DeviceCalendarMutations.prepare(getApplication(), request.first, request.second, request.third, sourceDate.value)
            mutableState.update { it.copy(providerEditor = record) }
        } else if (!allowed) viewModelScope.launch { mutableMessages.emit(t("Ohne Schreibfreigabe bleiben Gerätekalender schreibgeschützt.")) }
    }
    fun dismissProviderEdit() { mutableState.update { it.copy(providerEditor = null, eventError = null) } }
    fun saveProviderEvent(event: Event) = eventOperation {
        val record = mutableState.value.providerEditor ?: return@eventOperation
        de.luma.calendar.integrations.DeviceCalendarMutations.write(getApplication(), record, event)
        mutableState.update { it.copy(providerEditor = null, editCompletion = it.editCompletion + 1, savedWithReminder = false) }
        connections.refresh(); mutableMessages.emit(t("Gerätekalender aktualisiert"))
    }
    fun prepareTasks(uri: Uri) = operation {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")
        val bytes = stream.use { de.luma.calendar.integrations.AttachmentStore.bounded(it, CalendarFiles.MAX_BYTES) }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val records = de.luma.calendar.integrations.TaskExchange.read(text, mutableState.value.settings.defaultCalendarId)
        val preview = de.luma.calendar.integrations.TaskExchange.preview(database, records)
        mutableState.update { it.copy(pendingTasks = preview) }
    }
    fun dismissTasks() { mutableState.update { it.copy(pendingTasks = null) } }
    fun confirmTasks(replace: Set<String>) = operation {
        val preview = mutableState.value.pendingTasks ?: return@operation
        de.luma.calendar.integrations.TaskExchange.apply(database, preview, replace)
        mutableState.update { it.copy(pendingTasks = null) }; mutableMessages.emit(t("Aufgaben übernommen"))
    }
    fun exportTasks(uri: Uri) = operation {
        val prefs = getApplication<Application>().getSharedPreferences("task-exchange", 0)
        val namespace = prefs.getString("namespace", null) ?: java.util.UUID.randomUUID().toString().also { check(prefs.edit().putString("namespace", it).commit()) }
        val text = database.withTransaction { de.luma.calendar.integrations.TaskExchange.write(database.tasks().all(), database.integrations().origins(), namespace) }
        require(text.toByteArray().size <= CalendarFiles.MAX_BYTES)
        val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Datei kann nicht geöffnet werden.")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }; mutableMessages.emit(t("Aufgaben exportiert"))
    }
    fun saveTravel(plan: TravelPlan) = operation { database.withTransaction { require(database.events().find(plan.eventId)?.allDay == false) { "Abfahrtserinnerungen benötigen einen Termin mit Uhrzeit." }; database.integrations().travel(plan.checked()) }; mutableMessages.emit(t("Abfahrtserinnerung gespeichert")) }
    fun removeTravel(id: Long) = operation { database.integrations().removeTravel(id); de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(getApplication()) }
    fun clearWeather() = operation { de.luma.calendar.integrations.WeatherStore(getApplication()).clear(); getApplication<Application>().getSharedPreferences("weather-inputs", 0).edit().clear().commit(); de.luma.calendar.integrations.PlaceSearch(getApplication()).clear(); mutableMessages.emit(t("Wettercache entfernt")) }
    fun loadSchoolMetadata() = operation { try { schoolStore.metadata(mutableState.value.settings.schoolCountry) } finally { mutableState.update { it.copy(school = schoolStore.read(it.settings)) } } }
    fun fetchSchool(year: Int) = operation { val s = mutableState.value.settings; schoolStore.fetch(s.schoolCountry, s.schoolRegion, s.schoolGroup, year); mutableState.update { it.copy(school = schoolStore.read(it.settings)) }; CalendarWidgets.update(getApplication()); mutableMessages.emit(t("Schulferien geladen")) }
    fun attach(eventId: Long, uri: Uri) = operation { de.luma.calendar.integrations.AttachmentStore(getApplication()).add(eventId, uri); mutableMessages.emit(t("Anhang gespeichert")) }
    fun removeAttachment(id: String) = operation { de.luma.calendar.integrations.AttachmentStore(getApplication()).remove(id) }
    fun exportArchive(uri: Uri) = operation {
        val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Datei kann nicht geöffnet werden.")
        de.luma.calendar.data.AttachmentArchive.write(getApplication(), stream)
        mutableMessages.emit(t("ZIP-Sicherung mit Anhängen gespeichert"))
    }
    fun prepareArchive(uri: Uri) = operation {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")
        val archive = stream.use { de.luma.calendar.data.AttachmentArchive.read(it) }
        mutableState.update { it.copy(pendingArchive = archive, pendingImport = archive.backup) }
    }
    fun saveTemplate(template: EventTemplate) = operation { database.withTransaction {
        val value = template.checked()
        require(database.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        if (value.id == 0L) { require(database.templates().all().size < 1000) { "Maximal 1.000 Vorlagen." }; database.templates().insert(value) }
        else check(database.templates().update(value) == 1)
    }; mutableMessages.emit(t("Vorlage gespeichert")) }
    fun deleteTemplate(id: Long) = operation { database.templates().delete(id) }
    fun clearNoteError() { mutableState.update { it.copy(noteError = null) } }
    fun saveNote(note: CalendarNote) = operation {
        mutableState.update { it.copy(noteError = null) }
        try {
            NoteRepository(database).save(note)
            mutableState.update { it.copy(noteCompletion = it.noteCompletion + 1) }
            mutableMessages.emit(t("Notiz gespeichert"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(noteError = AppLocale.wrap(getApplication()).userError(error.message)) } }
    }
    fun toggleNoteItem(id: Long, itemId: String) = operation { NoteRepository(database).toggleItem(id, itemId) }
    fun pinNote(id: Long) = operation { NoteRepository(database).pin(id) }
    fun archiveNote(id: Long) = operation { NoteRepository(database).archive(id) }
    fun deleteNote(id: Long) = operation { NoteRepository(database).remove(id) }
    fun saveTask(task: CalendarTask) = operation { database.withTransaction {
        val value = task.checked()
        require(database.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        if (value.id == 0L) { require(database.tasks().all().size < 10000) { "Maximal 10.000 Aufgaben." }; database.tasks().insert(value) }
        else check(database.tasks().update(value) == 1)
    }; mutableMessages.emit(t("Aufgabe gespeichert")) }
    fun finishTask(task: CalendarTask) = operation { database.withTransaction {
        val current = database.tasks().find(task.id) ?: error("Bitte Eingaben prüfen.")
        require(current == task) { "Bitte Eingaben prüfen." }
        database.tasks().update(current.finish())
    }; mutableMessages.emit(t(if (task.repeat == Repeat.NONE) "Aufgabe aktualisiert" else "Erledigt. Die nächste Wiederholung ist eingeplant.")) }
    fun deleteTask(task: CalendarTask) = operation { database.tasks().delete(task.id); de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(getApplication()) }
    fun save(event: Event) = eventOperation {
        require(event.id >= 0 && event.category != Category.HOLIDAY) { "Ungültige Feiertagsdaten." }
        val checked = event.checked()
        val share = mutableState.value.sharedFile
        if (share != null && checked.id == 0L) de.luma.calendar.integrations.AttachmentStore(getApplication()).createFromShare(checked, share) else eventRepository.save(checked)
        mutableState.update { it.copy(sharedFile = null) }
        mutableState.update { it.copy(editCompletion = it.editCompletion + 1, savedWithReminder = checked.reminderOffsets.isNotEmpty()) }
        mutableMessages.emit(if (event.id == 0L) t("Termin angelegt") else t("Termin aktualisiert"))
    }
    fun saveOccurrence(parent: Event, originalStart: String, event: Event) = eventOperation {
        eventRepository.exception(parent, originalStart, event)
        finishOccurrenceEdit(event.reminderOffsets.isNotEmpty())
        mutableMessages.emit(t("Einzeltermin gespeichert"))
    }
    fun saveFuture(parent: Event, originalStart: String, event: Event) = eventOperation {
        eventRepository.saveFuture(parent, originalStart, event)
        finishOccurrenceEdit(event.reminderOffsets.isNotEmpty())
        mutableMessages.emit(t("Serie ab diesem Termin gespeichert"))
    }
    fun skipOccurrence(parent: Event, originalStart: String) = operation {
        eventRepository.exception(parent, originalStart, null)
        finishOccurrenceEdit(false)
        mutableMessages.emit(t("Dieses Vorkommen wurde ausgelassen."))
    }
    fun restoreOccurrence(id: Long, originalStart: String) = operation {
        eventRepository.restore(id, originalStart)
        finishOccurrenceEdit(false)
        mutableMessages.emit(t("Ursprüngliches Vorkommen wiederhergestellt"))
    }
    private fun finishOccurrenceEdit(reminder: Boolean) {
        mutableState.update { it.copy(editCompletion = it.editCompletion + 1, savedWithReminder = reminder) }
    }
    fun delete(event: Event) = operation {
        require(event.id > 0) { "Ungültige Feiertagsdaten." }
        database.events().delete(event.id)
        de.luma.calendar.integrations.AttachmentStore(getApplication()).collectGarbage()
        ReminderScheduler.cancel(getApplication(), event)
        de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(getApplication())
        mutableState.update { it.copy(editCompletion = it.editCompletion + 1, savedWithReminder = false) }
        mutableMessages.emit(t("Termin entfernt"))
    }
    fun export(uri: Uri, ics: Boolean) = operation {
        val text = database.withTransaction {
            val events = database.events().all()
            if (ics) IcsFiles.write(events, database.calendars().all(), database.exceptions().all(), database.categories().all()) else CalendarFiles.backup(events, database.calendars().all(), database.exceptions().all(), database.tasks().all(), database.templates().all(), database.integrations().travel(), database.categories().all(), database.notes().all())
        }
        require(text.toByteArray().size <= CalendarFiles.MAX_BYTES) { "Der Export ist zu groß (maximal 10 MB)." }
        val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Datei kann nicht geöffnet werden.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        mutableMessages.emit(if (ics) t("Kalender mit vollständigen Serien exportiert") else t("Terminsicherung gespeichert"))
    }
    fun prepareImport(uri: Uri, ics: Boolean = false) = operation {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= CalendarFiles.MAX_BYTES) { "Die Sicherung ist zu groß (maximal 10 MB)." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= CalendarFiles.MAX_BYTES) { "Die Sicherung ist zu groß (maximal 10 MB)." }
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val backup = if (ics) IcsFiles.read(content) else CalendarFiles.readCalendarBackup(content)
        require(backup.events.isNotEmpty() || backup.calendars.isNotEmpty() || backup.tasks.isNotEmpty() || backup.notes.isNotEmpty()) { "Diese Sicherung enthält keine Termine." }
        mutableState.update { it.copy(pendingImport = backup) }
    }
    fun importContactBirthdays() = operation {
        val backup = de.luma.calendar.integrations.ContactBirthdays.read(getApplication<Application>().contentResolver)
        mutableState.update { it.copy(pendingImport = backup) }
    }
    fun loadDeviceCalendars(connect: Boolean = false) = operation {
        val calendars = de.luma.calendar.integrations.DeviceCalendars.list(getApplication<Application>().contentResolver)
        mutableState.update { it.copy(deviceCalendars = calendars, connectDeviceCalendar = connect) }
    }
    fun sourceDate(date: LocalDate) { sourceDate.value = date.coerceIn(LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31)) }
    fun refreshDeviceSources() { connections.refresh() }
    fun connectDeviceCalendar(calendar: DeviceCalendar) = operation {
        connections.connect(calendar)
        mutableState.update { it.copy(deviceCalendars = null) }
        mutableMessages.emit(t("Gerätekalender verbunden"))
    }
    fun disconnectDeviceCalendar(id: Long) = operation {
        connections.disconnect(id)
        mutableMessages.emit(t("Verbindung entfernt. Der ursprüngliche Kalender bleibt erhalten."))
    }
    fun dismissDeviceCalendars() { mutableState.update { it.copy(deviceCalendars = null) } }
    fun importDeviceCalendar(calendar: de.luma.calendar.integrations.DeviceCalendar) = operation {
        val today = LocalDate.now()
        val backup = de.luma.calendar.integrations.DeviceCalendars.snapshot(getApplication<Application>().contentResolver, calendar, today, today.plusYears(1))
        mutableState.update { it.copy(deviceCalendars = null, pendingImport = backup) }
    }
    fun dismissImport() { mutableState.update { it.copy(pendingImport = null, pendingArchive = null) } }
    fun confirmImport() = operation {
        val backup = mutableState.value.pendingImport ?: return@operation
        val count = mutableState.value.pendingArchive?.let { de.luma.calendar.integrations.AttachmentStore(getApplication()).restore(it) } ?: calendarRepository.import(backup) { t(it) }
        mutableState.update { it.copy(pendingImport = null, pendingArchive = null) }
        mutableMessages.emit(if (backup.notes.isEmpty()) t("%1\$s Termine hinzugefügt", count) else t("%1\$s Termine und %2\$s Notizen hinzugefügt", count, backup.notes.size))
    }
    fun clearEventError() { mutableState.update { it.copy(eventError = null) } }
    private fun eventOperation(block: suspend () -> Unit) {
        clearEventError()
        operation {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(eventError = AppLocale.wrap(getApplication()).userError(error.message)) }
                throw error
            }
        }
    }
    fun clearCalendarError() { mutableState.update { it.copy(calendarError = null) } }
    fun saveCalendar(calendar: UserCalendar) = calendarOperation {
        calendarRepository.save(calendar) { t(it) }
        mutableState.update { it.copy(calendarCompletion = it.calendarCompletion + 1) }
        CalendarWidgets.update(getApplication())
        mutableMessages.emit(t("Kalender gespeichert"))
    }
    fun removeCalendar(source: Long, target: Long) = calendarOperation {
        calendarRepository.removeAndMove(source, target)
        val settings = mutableState.value.settings
        if (settings.defaultCalendarId == source) this.settings(settings.copy(defaultCalendarId = target))
        mutableState.update { it.copy(calendarCompletion = it.calendarCompletion + 1) }
        CalendarWidgets.update(getApplication())
        mutableMessages.emit(t("Kalender entfernt. Termine wurden übernommen."))
    }
    fun defaultCalendar(id: Long) = calendarOperation {
        require(database.calendars().find(id) != null) { "Bitte einen vorhandenen Kalender wählen." }
        settings(mutableState.value.settings.copy(defaultCalendarId = id))
    }
    private fun calendarOperation(block: suspend () -> Unit) {
        clearCalendarError()
        operation {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(calendarError = AppLocale.wrap(getApplication()).userError(error.message)) }
                throw error
            }
        }
    }
    fun fetchHolidays(year: Int) = operation {
        val country = mutableState.value.settings.holidayCountry
        try { holidayStore.fetch(country, year) }
        catch (error: Exception) { throw IllegalStateException("Feiertage konnten nicht geladen werden. Bereits geladene Daten bleiben erhalten.", error) }
        mutableState.update { it.copy(holidays = holidayStore.read(), school = schoolStore.read(mutableState.value.settings), holidaysLoaded = true) }
        CalendarWidgets.update(getApplication())
        mutableMessages.emit(t("Feiertage geladen"))
    }
    fun importHolidays(uri: Uri) = operation {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.")
        holidayStore.importCalendarIcs(stream.use { HolidayStore.boundedText(it) })
        val settings = mutableState.value.settings.copy(importedHolidays = true)
        settingsStore.write(settings)
        mutableState.update { it.copy(holidays = holidayStore.read(), settings = settings) }
        CalendarWidgets.update(getApplication())
        mutableMessages.emit(t("Feiertagsdatei importiert"))
    }
    fun removeHolidayImport() = operation {
        holidayStore.removeImport()
        mutableState.update { it.copy(holidays = holidayStore.read(), school = schoolStore.read(mutableState.value.settings), holidaysLoaded = true) }
        CalendarWidgets.update(getApplication())
        mutableMessages.emit(t("Feiertagsdatei entfernt"))
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableMessages.emit(AppLocale.wrap(getApplication()).userError(error.message)) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
