package de.luma.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import de.luma.calendar.*
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

fun LocalDate.pretty(locale: Locale) = format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEEE, d. MMMM" else "EEEE, d MMMM", locale))
fun Occurrence.timeLabel(t: Texts): String {
    if (event.allDay) return t("Ganztägig")
    val zone = ZoneId.systemDefault()
    val start = start.withZoneSameInstant(zone)
    val end = end.withZoneSameInstant(zone)
    return start.format(DateTimeFormatter.ofPattern("HH:mm")) + " – " + end.format(DateTimeFormatter.ofPattern(if (start.toLocalDate() == end.toLocalDate()) "HH:mm" else if (t.locale.language == "de") "dd.MM. HH:mm" else "d MMM HH:mm", t.locale))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarApp(state: CalendarState, model: CalendarViewModel, target: LaunchTarget, permissionMessage: String?, clearPermission: () -> Unit,
    onNotifications: () -> Unit, onPrecise: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onIcs: () -> Unit, onWidget: (Boolean) -> Unit, onLanguage: (AppLanguage) -> Unit, onHolidayImport: () -> Unit, onEventImport: () -> Unit = {}, onDeviceCalendars: () -> Unit = {}, onContactBirthdays: () -> Unit = {}, onConnectDeviceCalendars: () -> Unit = {}) {
    val t = texts()
    val locale = t.locale
    val displayCalendars = remember(state.calendars, state.deviceSources) { state.calendars + state.deviceSources.calendars }
    val calendarEvents = remember(state.events, state.deviceSources, state.holidays, state.school, state.settings, locale) { state.events + state.deviceSources.events + state.holidays.events(state.settings, locale) + state.school.events(state.settings, locale) }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var view by rememberSaveable { mutableStateOf(state.settings.startView.name) }
    var pages by rememberSaveable { mutableStateOf(listOf("calendar")) }
    val page = pages.last()
    val pageStates = rememberSaveableStateHolder()
    val viewStates = rememberSaveableStateHolder()
    var taskTabRequest by rememberSaveable { mutableLongStateOf(0) }
    fun navigate(destination: String, root: Boolean = false) {
        if (destination == page && !root) return
        pages = if (root) if (destination == "calendar") listOf("calendar") else listOf("calendar", destination) else pages + destination
    }
    fun openTasks() { taskTabRequest++; navigate("tasks") }
    fun back() { pages = pages.dropLast(1).ifEmpty { listOf("calendar") } }
    var noteCreation by rememberSaveable { mutableLongStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var activeCategory by rememberSaveable { mutableStateOf<Long?>(null) }
    var activeCalendar by rememberSaveable { mutableStateOf<Long?>(null) }
    var selected by rememberSaveable { mutableStateOf<Occurrence?>(null) }
    var editor by rememberSaveable { mutableStateOf<Event?>(null) }
    var occurrenceParent by rememberSaveable { mutableStateOf<Event?>(null) }
    var occurrenceEdit by rememberSaveable { mutableStateOf<Occurrence?>(null) }
    var managedSeries by rememberSaveable { mutableStateOf<Long?>(null) }
    var futureStart by rememberSaveable { mutableStateOf<String?>(null) }
    var futurePending by rememberSaveable { mutableStateOf<Event?>(null) }
    var resetSeries by rememberSaveable { mutableStateOf<Event?>(null) }
    var jump by remember { mutableStateOf(false) }
    var launchConsumed by rememberSaveable { mutableLongStateOf(0L) }
    var editConsumed by rememberSaveable { mutableLongStateOf(state.editCompletion) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun draft(day: LocalDate, hour: Int = 9): Event {
        val chosen = state.calendars.firstOrNull { it.id == activeCalendar }
            ?: state.calendars.firstOrNull { it.id == state.settings.defaultCalendarId } ?: state.calendars.firstOrNull()
        return newEvent(day, hour, chosen?.id ?: 1)
    }
    LaunchedEffect(date, page) { model.sourceDate(if (page == "overview") LocalDate.now() else date) }
    LaunchedEffect(state.deviceSources) {
        if (!state.sourcesLoading && selected?.event?.calendarId?.let { it < 0 } == true && state.deviceSources.events.none { it.id == selected?.event?.id }) selected = null
    }
    LaunchedEffect(state.categories) { if (!state.loading && activeCategory != null && state.categories.none { it.id == activeCategory }) activeCategory = null }
    LaunchedEffect(displayCalendars) {
        if (!state.loading && activeCalendar != null && activeCalendar != 0L && displayCalendars.none { it.id == activeCalendar }) activeCalendar = null
    }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(permissionMessage) { if (permissionMessage != null) { snackbar.showSnackbar(permissionMessage); clearPermission() } }
    LaunchedEffect(state.editCompletion) {
        if (state.editCompletion != editConsumed) {
            editor = null; occurrenceEdit = null; occurrenceParent = null; futureStart = null; futurePending = null; resetSeries = null; selected = null; editConsumed = state.editCompletion
            if (state.savedWithReminder) onNotifications()
        }
    }
    LaunchedEffect(target, state.loading, state.holidaysLoaded, state.sourcesLoading, calendarEvents) {
        if (!state.loading && state.holidaysLoaded && !state.sourcesLoading && launchConsumed != target.token) {
            if (target.navigate) { dateText = target.date.toString(); navigate(target.page ?: "calendar", root = true); if (target.page == "tasks") taskTabRequest++ }
            if (target.add) { occurrenceEdit = null; editor = draft(target.date).let { event -> event.copy(title = target.sharedTitle ?: event.title, notes = target.sharedNotes ?: event.notes) } }
            if (target.eventId != -1L) selected = EventEngine.occurrences(calendarEvents.filter { it.id == target.eventId }, target.date, target.date.plusDays(1), exceptions = state.exceptions).firstOrNull { target.occurrenceKey == null || it.key == target.occurrenceKey }
            launchConsumed = target.token
        }
    }
    BackHandler(pages.size > 1 && editor == null && selected == null) { back() }
    val words = (if (page == "search") search else "").trim().lowercase(locale).split(Regex("\\s+")).filter { it.isNotBlank() }
    fun matches(event: Event): Boolean = (activeCalendar == null || event.calendarId == activeCalendar) && (activeCategory == null || event.categoryId == activeCategory) &&
        words.all { word -> "${event.title} ${event.location} ${event.notes} ${event.calendarLabel(displayCalendars) { t(it) }} ${state.categories.firstOrNull { it.id == event.categoryId }?.name.orEmpty()}".lowercase(locale).contains(word) }
    val filtered = remember(calendarEvents, displayCalendars, state.exceptions, activeCalendar, activeCategory, state.categories, search, page, locale) {
        val matchingParents = state.exceptions.filter { it.replacement?.let(::matches) == true }.map { it.eventId }.toSet()
        calendarEvents.filter { matches(it) || it.id in matchingParents }
    }
    fun displayed(from: LocalDate, to: LocalDate) = EventEngine.occurrences(filtered, from, to, exceptions = state.exceptions, filterEvent = ::matches)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Triple("calendar", t("Kalender"), Icons.Calendar), Triple("agenda", t("Agenda"), Icons.Agenda), Triple("create", t(if (page == "tasks") "Neue Notiz" else "Neuer Termin"), Icons.Add), Triple("tasks", t("Notizen"), Icons.Notes), Triple("shared", t("Gemeinsam"), Icons.People)).forEach { (key, label, icon) ->
                    NavigationBarItem(page == key, { if (key == "create") { if (page == "tasks") noteCreation++ else { occurrenceEdit = null; editor = draft(date) } } else navigate(key, root = true) }, modifier = Modifier.testTag("nav-$key").semantics { if (key == "create") contentDescription = label }, icon = { if (key == "create") Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(icon, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimary) } else Icon(icon, label) }, label = { Text(if (key == "create") t("Hinzufügen") else label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            }
        },
        floatingActionButton = {},
    ) { padding ->
        pageStates.SaveableStateProvider(page) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page == "overview") {
                if (state.loading || state.sourcesLoading || !state.holidaysLoaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else PlanningOverview(state, EventEngine.occurrences(calendarEvents, LocalDate.now(), LocalDate.now().plusDays(7), exceptions = state.exceptions), { selected = it }, { dateText = it.toString(); navigate("calendar") }, { openTasks() }, { navigate("categories") })
            }
            else if (page == "shared") SharedCalendarHub(state, model, onConnectDeviceCalendars)
            else if (page == "categories") CategoryManager(state, model) { back() }
            else if (page == "integrations") IntegrationSettings(state, model) { back() }
            else if (page == "sources") DeviceSourceManager(state, model, { back() }, onConnectDeviceCalendars)
            else if (page == "templates") TemplateManager(state, model) { back() }
            else if (page == "tasks") TaskManager(state, model, onNotifications, noteCreation, taskTabRequest)
            else if (page == "series") SeriesManager(state, model, { back() }, { event -> occurrenceEdit = null; editor = event }, { managedSeries = it.id })
            else if (page == "calendars") CalendarManager(state, model) { back() }
            else if (page == "settings") CalendarSettings(state, model::settings, { back() }, onNotifications, onPrecise, onBackup, onRestore, onIcs, onWidget, onLanguage, onHolidayImport, model::fetchHolidays, model::removeHolidayImport, onManageCategories = { navigate("categories") }, onSharedCalendars = { navigate("shared") }, onManageCalendars = { navigate("calendars") }, onManageSeries = { navigate("series") }, onEventImport = onEventImport, onDeviceCalendars = onDeviceCalendars, onManageTemplates = { navigate("templates") }, onContactBirthdays = onContactBirthdays, onManageSources = { navigate("sources") }, onIntegrations = { navigate("integrations") })
            else {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Gimbo Kalender"), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Text(t(when (page) { "agenda" -> "Agenda"; "search" -> "Suche"; else -> "Deine Pläne im Blick." }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ navigate("overview") }, Modifier.testTag("nav-overview")) { Icon(Icons.Overview, t("Übersicht")) }
                    IconButton({ navigate("search") }, Modifier.testTag("nav-search")) { Icon(Icons.Search, t("Suche")) }
                    IconButton(onClick = { navigate("settings") }, modifier = Modifier.testTag("nav-settings")) { Icon(Icons.Settings, t("Einstellungen")) }
                }
                if (page == "search") OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    placeholder = { Text(t("Titel, Ort oder Notiz suchen")) }, leadingIcon = { Icon(Icons.Search, null) }, singleLine = true,
                    trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Icons.Close, t("Suche leeren")) } }, shape = RoundedCornerShape(18.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(activeCalendar == null, { activeCalendar = null }, label = { Text(t("Alle")) })
                    displayCalendars.forEach { calendar -> FilterChip(activeCalendar == calendar.id, { activeCalendar = if (activeCalendar == calendar.id) null else calendar.id },
                        modifier = Modifier.testTag("filter-calendar-${calendar.id}"), label = { Text(calendar.displayName { t(it) } + if (calendar.id < 0) " · " + t("Gerätekalender") else "") },
                        leadingIcon = { Box(Modifier.size(7.dp).background(Color(calendar.color), CircleShape)) }) }
                    if (calendarEvents.any { it.category == Category.HOLIDAY }) FilterChip(activeCalendar == 0L, { activeCalendar = if (activeCalendar == 0L) null else 0L },
                        label = { Text(t("Feiertag")) }, leadingIcon = { Box(Modifier.size(7.dp).background(Color(Category.HOLIDAY.color), CircleShape)) })
                }
                if (state.categories.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(activeCategory == null, { activeCategory = null }, { Text(t("Alle Kategorien")) })
                    state.categories.forEach { category -> FilterChip(activeCategory == category.id, { activeCategory = if (activeCategory == category.id) null else category.id }, { Text(category.name) }, leadingIcon = { Box(Modifier.size(7.dp).background(Color(category.color), CircleShape)) }) }
                }
                if (state.sourcesLoading && state.deviceSources.sources.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.deviceSources.unavailable) TextButton({ navigate("sources") }, Modifier.fillMaxWidth()) { Text(t("Einige Gerätekalender sind nicht verfügbar. Verbindungen prüfen.")) }
                if (state.loading || !state.holidaysLoaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (state.error != null) Text(state.error, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                else if (page in listOf("agenda", "search")) {
                    val results = remember(filtered, state.exceptions, activeCalendar, activeCategory, state.categories, search, date, page, locale) {
                        displayed(if (page == "search") date.minusYears(1) else date, date.plusYears(1))
                    }
                    Text(if (page == "search") t("Treffer · %1\$s bis %2\$s", date.minusYears(1).year, date.plusYears(1).year) else t("Ab %1\$s · nächste 12 Monate", date.pretty(locale)),
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (results.size >= 10000) Text(t("Es werden höchstens 10.000 Vorkommen gezeigt. Bitte Kalender filtern oder den Zeitraum verkleinern."), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                    AgendaList(results, onOpen = { selected = it }, onAdd = { occurrenceEdit = null; editor = draft(date) })
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ dateText = move(date, CalendarView.valueOf(view), -1).toString() }) { Icon(Icons.Left, t("Vorheriger Zeitraum")) }
                        TextButton({ jump = true }, Modifier.weight(1f)) {
                            Text(if (view == CalendarView.MONTH.name) date.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)) else date.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM yyyy" else "d MMM yyyy", locale)), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton({ dateText = move(date, CalendarView.valueOf(view), 1).toString() }) { Icon(Icons.Right, t("Nächster Zeitraum")) }
                        TextButton({ dateText = LocalDate.now().toString() }) { Text(t("Heute")) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalendarView.entries.forEach { mode -> FilterChip(view == mode.name, { view = mode.name }, { Text(t(mode.label)) }) }
                    }
                    viewStates.SaveableStateProvider(view) {
                    when (CalendarView.valueOf(view)) {
                        CalendarView.MONTH -> {
                            val month = YearMonth.from(date)
                            val first = month.atDay(1)
                            val start = first.minusDays((if (state.settings.mondayFirst) first.dayOfWeek.value - 1 else first.dayOfWeek.value % 7).toLong())
                            val occurrences = remember(filtered, state.exceptions, activeCalendar, activeCategory, state.categories, search, start, locale) { displayed(start, start.plusDays(42)) }
                            val day = occurrences.filter { overlapsDay(it, date) }
                            LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
                                if (occurrences.size >= 10000) item { Text(t("Es werden höchstens 10.000 Vorkommen gezeigt. Bitte Kalender filtern oder den Zeitraum verkleinern."), Modifier.padding(20.dp)) }
                                item { MonthGrid(date, start, occurrences, state.settings, { dateText = it.toString() }) }
                                item { Text(date.pretty(locale), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontWeight = FontWeight.SemiBold) }
                                if (day.isEmpty()) item { EmptyAgenda { occurrenceEdit = null; editor = draft(date) } }
                                items(day, key = { it.key }) { occurrence -> Box(Modifier.padding(horizontal = 20.dp)) { EventCard(occurrence) { selected = occurrence } } }
                            }
                        }
                        CalendarView.WEEK -> {
                            val start = date.minusDays((if (state.settings.mondayFirst) date.dayOfWeek.value - 1 else date.dayOfWeek.value % 7).toLong())
                            val occurrences = remember(filtered, state.exceptions, activeCalendar, activeCategory, state.categories, search, start, locale) { displayed(start, start.plusDays(7)) }
                            if (occurrences.size >= 10000) Text(t("Es werden höchstens 10.000 Vorkommen gezeigt. Bitte Kalender filtern oder den Zeitraum verkleinern."))
                            WeekTimeline(start, occurrences, { selected = it }, { day, hour -> occurrenceEdit = null; editor = draft(day, hour) }, onDay = { dateText = it.toString(); view = CalendarView.DAY.name }) { occurrence, local ->
                                val parent = state.events.firstOrNull { it.id == occurrence.event.id }
                                if (parent != null) {
                                    val draft = occurrence.asSingleEvent()
                                    val shifted = local.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of(draft.zone)).toLocalDateTime()
                                    val duration = Duration.between(LocalDateTime.parse(draft.start), LocalDateTime.parse(draft.end))
                                    occurrenceEdit = if (occurrence.originalStart != null) occurrence else null
                                    occurrenceParent = parent
                                    editor = draft.copy(start = shifted.toString(), end = shifted.plus(duration).toString())
                                }
                            }
                        }
                        CalendarView.DAY -> {
                            val occurrences = remember(filtered, state.exceptions, activeCalendar, activeCategory, state.categories, search, date, locale) { displayed(date, date.plusDays(1)) }
                            if (occurrences.size >= 10000) Text(t("Es werden höchstens 10.000 Vorkommen gezeigt. Bitte Kalender filtern oder den Zeitraum verkleinern."))
                            DayTimeline(date, occurrences, { selected = it }, { hour -> occurrenceEdit = null; editor = draft(date, hour) })
                        }
                        CalendarView.AGENDA -> {
                            val occurrences = remember(filtered, state.exceptions, activeCalendar, activeCategory, state.categories, search, date, locale) { displayed(date, date.plusMonths(3)) }
                            if (occurrences.size >= 10000) Text(t("Es werden höchstens 10.000 Vorkommen gezeigt. Bitte Kalender filtern oder den Zeitraum verkleinern."))
                            AgendaList(occurrences, { selected = it }, { occurrenceEdit = null; editor = draft(date) })
                        }
                    }
                    }
                }
            }
        }
    }
    }
    if (jump) DatePickerDialog(date, { jump = false }, { dateText = it.toString(); jump = false })
    selected?.let { original ->
        val parent = calendarEvents.firstOrNull { it.id == original.event.id } ?: original.event
        val selectedDate = original.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        val occurrence = EventEngine.occurrences(listOf(parent), selectedDate.minusDays(1), selectedDate.plusDays(2), exceptions = state.exceptions)
            .firstOrNull { it.key == original.key } ?: original
        EventDetails(occurrence, state.busy, { selected = null },
            onEdit = { selected = null; occurrenceEdit = null; editor = parent },
            onDuplicate = { selected = null; occurrenceEdit = null; editor = occurrence.asSingleEvent().copy(id = 0, calendarId = if (occurrence.event.calendarId < 0) draft(date).calendarId else occurrence.event.calendarId, title = t("%1\$s (Kopie)", occurrence.event.title.take(190))) },
            onDelete = { model.delete(parent) },
            onEditOccurrence = { selected = null; occurrenceEdit = occurrence; occurrenceParent = parent; editor = occurrence.asSingleEvent() },
            onSkip = { occurrence.originalStart?.let { model.skipOccurrence(parent, it) } },
            onRestore = { occurrence.originalStart?.let { model.restoreOccurrence(parent.id, it) } },
            onManage = { managedSeries = parent.id; selected = null },
            onTemplate = { name -> model.saveTemplate(EventTemplate(name = name, event = (if (occurrence.originalStart != null && !occurrence.changed) parent.futureDraft(occurrence.originalStart) else occurrence.asSingleEvent()).copy(id = 0))); selected = null },
            onEditFuture = { selected = null; occurrenceEdit = null; occurrenceParent = parent; futureStart = occurrence.originalStart; editor = parent.futureDraft(occurrence.originalStart!!) }, sourceEventId = state.deviceSources.providerIds[occurrence.event.id],
            onEditSource = { state.deviceSources.sources.firstOrNull { it.calendar.displayId == occurrence.event.calendarId }?.let { source -> model.beginProviderEdit(source.calendar, state.deviceSources.providerIds[occurrence.event.id]); selected = null } },
            onDeleteSource = { state.deviceSources.sources.firstOrNull { it.calendar.displayId == occurrence.event.calendarId }?.let { source -> model.beginProviderEdit(source.calendar, state.deviceSources.providerIds[occurrence.event.id], true); selected = null } },
            extras = { AttachmentSection(occurrence.event, state, model); TravelWeather(occurrence.asSingleEvent(), state, model, onNotifications) })
    }
    TaskImportDialog(state, model)
    state.providerEditor?.let { record ->
        if (record.remove) AlertDialog(onDismissRequest = model::dismissProviderEdit, title = { Text(t("Termin im Originalkalender entfernen?")) }, text = { Column { Text(t("„%1\$s“ wird aus „%2\$s“ entfernt. Der Android-Anbieter synchronisiert die Löschung.", record.draft.title, record.calendar.name)); state.eventError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton({ model.saveProviderEvent(record.draft) }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton(model::dismissProviderEdit, enabled = !state.busy) { Text(t("Abbrechen")) } })
        else key(record) { EventEditor(record.draft, state.busy, model::dismissProviderEdit, singleOccurrence = true, external = true, externalCalendarName = record.calendar.name, remoteError = state.eventError, onSave = model::saveProviderEvent) }
    }
    LaunchedEffect(editor, selected) { model.clearEventError() }
    editor?.let { event -> EventEditor(event, state.busy, { editor = null; occurrenceEdit = null; futureStart = null; futurePending = null; model.cancelSharedFile() }, sharedAttachment = state.sharedFile != null, templates = state.templates, futureOccurrence = futureStart != null, singleOccurrence = occurrenceEdit != null, remoteError = state.eventError) { value ->
        val occurrence = occurrenceEdit
        val old = state.events.firstOrNull { it.id == value.id }
        if (futureStart != null) futurePending = value
        else if (occurrence != null) {
            model.saveOccurrence(occurrenceParent ?: old ?: occurrence.event, occurrence.originalStart!!, value)
        } else if (old != null && old.recurrenceChanged(value) && state.exceptions.any { it.eventId == old.id }) resetSeries = value
        else model.save(value)
    } }
    futurePending?.let { value -> AlertDialog(onDismissRequest = { futurePending = null }, title = { Text(t("Serie ab hier ändern?")) },
        text = { Text(t(if (occurrenceParent!!.futureDraft(futureStart!!).recurrenceChanged(value))
            "Frühere Vorkommen und ihre Einzeländerungen bleiben erhalten. Einzeländerungen ab dem gewählten ursprünglichen Serientag werden zurückgesetzt."
            else "Frühere Vorkommen bleiben erhalten. Bestehende Einzeländerungen behalten ihre eigenen Werte. Die Zuordnung erfolgt nach dem ursprünglichen Serientag.")) },
        confirmButton = { TextButton({ model.saveFuture(occurrenceParent!!, futureStart!!, value); futurePending = null }, enabled = !state.busy) { Text(t("Ab hier speichern")) } },
        dismissButton = { TextButton({ futurePending = null }, enabled = !state.busy) { Text(t("Weiter bearbeiten")) } }) }
    resetSeries?.let { value -> AlertDialog(onDismissRequest = { resetSeries = null }, title = { Text(t("Einzeländerungen zurücksetzen?")) },
        text = { Text(t("Beginn, Zeitzone oder Wiederholung ändern den Serienrhythmus. Bisherige Einzeländerungen und ausgelassene Vorkommen werden zurückgesetzt.")) },
        confirmButton = { TextButton({ model.save(value); resetSeries = null }, enabled = !state.busy) { Text(t("Zurücksetzen & speichern")) } },
        dismissButton = { TextButton({ resetSeries = null }, enabled = !state.busy) { Text(t("Weiter bearbeiten")) } }) }
    managedSeries?.let { id -> state.events.firstOrNull { it.id == id }?.let { parent ->
        SeriesExceptions(parent, state.exceptions.filter { it.eventId == id }, state.busy, { managedSeries = null }) { start -> model.restoreOccurrence(id, start) }
    } }
    state.deviceCalendars?.let { DeviceCalendarPicker(it, state.busy, model::dismissDeviceCalendars, { c -> if (state.connectDeviceCalendar) model.connectDeviceCalendar(c) else model.importDeviceCalendar(c) }, connect = state.connectDeviceCalendar, connected = state.deviceSources.sources.map { it.calendar.identity }.toSet()) }
    state.pendingImport?.let { backup -> AlertDialog(onDismissRequest = { if (!state.busy) model.dismissImport() },
        title = { Text(t("%1\$s Termine, %2\$s Aufgaben, %3\$s Notizen und %4\$s Vorlagen hinzufügen?", backup.events.size, backup.tasks.size, backup.notes.size, backup.templates.size)) },
        text = { Text(t("Termine, Aufgaben, Notizen und Kalender werden hinzugefügt. Gleiche Kalender werden wiederverwendet; Namenskonflikte erhalten einen Importzusatz. Erneuter Import erzeugt doppelte Einträge.")) },
        confirmButton = { TextButton(onClick = model::confirmImport, enabled = !state.busy) { Text(t("Hinzufügen")) } },
        dismissButton = { TextButton(onClick = model::dismissImport, enabled = !state.busy) { Text(t("Abbrechen")) } }) }
}

fun newEvent(date: LocalDate, hour: Int = 9, calendarId: Long = 1): Event = Event(calendarId = calendarId, title = "", start = date.atTime(hour, 0).toString(), end = date.atTime(hour, 0).plusHours(1).toString())
fun move(date: LocalDate, view: CalendarView, step: Long): LocalDate = when (view) { CalendarView.MONTH -> date.plusMonths(step); CalendarView.WEEK -> date.plusWeeks(step); CalendarView.DAY -> date.plusDays(step); CalendarView.AGENDA -> date.plusMonths(step) }
fun overlapsDay(o: Occurrence, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = o.start.toInstant() < date.plusDays(1).atStartOfDay(zone).toInstant() && o.end.toInstant() > date.atStartOfDay(zone).toInstant()

@Composable
private fun MonthGrid(selected: LocalDate, start: LocalDate, events: List<Occurrence>, settings: AppSettings, onSelect: (LocalDate) -> Unit) {
    val t = texts()
    val locale = t.locale
    val calendars = LocalCalendars.current
    val categories = LocalCategories.current
    val categoryColors = remember(categories) { categories.associate { it.id to it.color } }
    fun paint(event: Event) = categoryColors[event.categoryId] ?: event.calendarColor(calendars)
    val byDay = remember(events, start) { val zone = ZoneId.systemDefault(); (0L..41L).associate { offset -> val day = start.plusDays(offset); day to events.filter { overlapsDay(it, day, zone) } } }
    val days = (0L..6L).map { LocalDate.of(2024, 1, if (settings.mondayFirst) 1 else 7).plusDays(it).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row {
            if (settings.weekNumbers) Text(t("KW"), Modifier.width(24.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            days.forEach { Text(it, Modifier.weight(1f).wrapContentWidth(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val rows = ((YearMonth.from(selected).lengthOfMonth() + java.time.temporal.ChronoUnit.DAYS.between(start, selected.withDayOfMonth(1)) + 6) / 7).toInt()
        repeat(rows) { row -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (settings.weekNumbers) Text(start.plusDays(row * 7L + if (settings.mondayFirst) 0 else 1).get(WeekFields.ISO.weekOfWeekBasedYear()).toString(), Modifier.width(24.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(7) { col ->
                val day = start.plusDays(row * 7L + col)
                val active = day == selected
                val today = day == LocalDate.now()
                val values = byDay[day].orEmpty()
                val holidayCount = values.count { it.event.category == Category.HOLIDAY }
                val detailed = settings.monthStyle == MonthStyle.DETAILED
                val weekend = day.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                val holidayColor = Color(Category.HOLIDAY.color)
                val tileColor = when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    holidayCount > 0 && settings.highlightHolidays -> holidayColor.copy(alpha = .16f)
                    values.isNotEmpty() -> Color(paint(values.first().event)).copy(alpha = .1f)
                    weekend && settings.highlightWeekends -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                Column(Modifier.weight(1f).heightIn(min = if (detailed) 78.dp else 54.dp).padding(2.dp)
                    .background(tileColor, RoundedCornerShape(14.dp))
                    .border(if (today || active) 2.dp else 0.dp, if (active) MaterialTheme.colorScheme.primary else if (today) MaterialTheme.colorScheme.primary.copy(alpha = .7f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .testTag("calendar-day-$day").semantics { contentDescription = day.pretty(locale) + " · " + t("%1\$s Termine · %2\$s Feiertage", values.size, holidayCount); this.selected = active }
                    .clickable(onClickLabel = day.pretty(locale)) { onSelect(day) }.padding(horizontal = 2.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.dayOfMonth.toString(), color = if (day.month != selected.month) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f) else if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp, fontWeight = if (today || active || values.isNotEmpty()) FontWeight.Bold else FontWeight.Normal)
                    if (detailed) {
                        values.take(2).forEach { occurrence ->
                            val color = paint(occurrence.event)
                            Surface(Modifier.fillMaxWidth().padding(top = 2.dp), color = Color(color).copy(alpha = .3f), shape = RoundedCornerShape(5.dp)) {
                                Text(occurrence.event.title, Modifier.padding(horizontal = 3.dp, vertical = 1.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 12.sp,
                                    color = Color(readableAccent(color.toInt(), androidx.core.graphics.ColorUtils.compositeColors(Color(color).copy(alpha = .3f).toArgb(), androidx.core.graphics.ColorUtils.compositeColors(tileColor.toArgb(), MaterialTheme.colorScheme.surface.toArgb())))))
                            }
                        }
                        if (values.size > 2 && settings.eventCounts) Text("+${values.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.height(12.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            values.map { paint(it.event) }.distinct().take(3).forEach { color -> Box(Modifier.size(7.dp).background(Color(color), CircleShape)) }
                        }
                        if (values.isNotEmpty() && settings.eventCounts) Text(values.size.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } }
    }
}

@Composable
fun EventCard(occurrence: Occurrence, onClick: () -> Unit) {
    val t = texts()
    val locale = t.locale
    val tint = Color(occurrence.event.presentationColor())
    val now by produceState(Instant.now(), occurrence.key) { while (true) { kotlinx.coroutines.delay(60000); value = Instant.now() } }
    val ongoing = !occurrence.event.allDay && now >= occurrence.start.toInstant() && now < occurrence.end.toInstant()
    val holiday = occurrence.event.category == Category.HOLIDAY
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("occurrence-${occurrence.key}").clickable(onClick = onClick), shape = RoundedCornerShape(22.dp),
        color = tint.copy(alpha = if (ongoing || holiday) .18f else .1f), border = BorderStroke(if (ongoing) 2.dp else 1.dp, tint.copy(alpha = if (ongoing) .9f else .35f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(38.dp).background(Color(occurrence.event.presentationColor()), CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                LocalCategories.current.firstOrNull { it.id == occurrence.event.categoryId }?.let { Text(it.name, color = Color(readableAccent(it.color.toInt(), MaterialTheme.colorScheme.surfaceContainer.toArgb())), style = MaterialTheme.typography.labelSmall) }
                if (ongoing) Text(t("Jetzt"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                if (holiday) Text(t("Feiertag"), style = MaterialTheme.typography.labelSmall, color = Color(readableAccent(occurrence.event.presentationColor().toInt(), MaterialTheme.colorScheme.surface.toArgb())))
                Text(occurrence.event.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(occurrence.timeLabel(t), occurrence.event.location).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (occurrence.originalStart != null) Icon(Icons.Repeat, t(if (occurrence.changed) "Einzeländerung einer Serie" else "Serientermin"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgendaList(events: List<Occurrence>, onOpen: (Occurrence) -> Unit, onAdd: () -> Unit, grouped: Boolean = true) {
    val t = texts()
    val locale = t.locale
    if (events.isEmpty()) EmptyAgenda(onAdd) else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp)) {
        val groups = if (grouped) events.groupBy { it.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate() } else mapOf(LocalDate.MIN to events)
        groups.forEach { (date, items) ->
            if (grouped) item(key = "header-$date") { Text(date.pretty(locale), Modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
            items(items, key = { it.key }) { occurrence -> EventCard(occurrence) { onOpen(occurrence) } }
        }
    }
}

@Composable
private fun EmptyAgenda(onAdd: () -> Unit) {
    val t = texts()
    val locale = t.locale
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Calendar, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        Text(t("Platz für deine Pläne."), Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
        Text(t("Hier ist noch kein Termin eingetragen."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onAdd) { Text(t("Termin hinzufügen")) }
    }
}

@Composable
private fun DayTimeline(date: LocalDate, events: List<Occurrence>, onOpen: (Occurrence) -> Unit, onAdd: (Int) -> Unit) {
    val t = texts()
    val locale = t.locale
    val initialHour = if (date == LocalDate.now()) maxOf(0, LocalTime.now().hour - 1) else 8
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialHour + if (events.any { it.event.allDay }) 1 else 0)
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp)) {
        val allDay = events.filter { it.event.allDay }
        if (allDay.isNotEmpty()) item { Text(t("GANZTÄGIG"), style = MaterialTheme.typography.labelSmall); allDay.forEach { EventCard(it) { onOpen(it) } } }
        items((0..23).toList(), key = { it }) { hour ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.Top) {
                Text("%02d:00".format(hour), Modifier.width(52.dp).padding(top = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val atHour = events.filter { !it.event.allDay && maxOf(it.start.toInstant(), date.atStartOfDay(ZoneId.systemDefault()).toInstant()).atZone(ZoneId.systemDefault()).hour == hour }
                    if (atHour.isEmpty()) Box(Modifier.fillMaxWidth().height(62.dp).clickable(onClickLabel = t("Termin um %1\$s Uhr hinzufügen", hour)) { onAdd(hour) })
                    atHour.forEach { EventCard(it) { onOpen(it) } }
                }
            }
        }
    }
}
