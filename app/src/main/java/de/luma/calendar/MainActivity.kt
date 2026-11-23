package de.luma.calendar

import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luma.calendar.ui.*
import de.luma.calendar.i18n.*
import de.luma.calendar.reminders.ReminderScheduler
import de.luma.calendar.widget.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LaunchTarget(val date: LocalDate = LocalDate.now(), val eventId: Long = -1, val add: Boolean = false, val token: Long = System.nanoTime(), val navigate: Boolean = false, val occurrenceKey: String? = null, val page: String? = null, val sharedTitle: String? = null, val sharedNotes: String? = null)
class MainActivity : ComponentActivity() {
    private val model: CalendarViewModel by viewModels { androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application) }
    private var target by mutableStateOf(LaunchTarget())
    private var connectCalendar = false
    private var permissionMessage by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionMessage = if (allowed) AppLocale.wrap(this).text("Terminerinnerungen sind aktiviert.") else AppLocale.wrap(this).text("Ohne Benachrichtigungsfreigabe bleiben Erinnerungen stumm; Freigabe später in den Einstellungen möglich.")
    }
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.importContactBirthdays() else permissionMessage = AppLocale.wrap(this).text("Ohne Kontaktfreigabe ist kein Geburtstagsimport möglich.")
    }
    private val writeCalendarPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.resumeProviderEdit(de.luma.calendar.integrations.DeviceCalendarMutations.allowed(this)) }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.loadDeviceCalendars(connectCalendar) else permissionMessage = AppLocale.wrap(this).text("Ohne Kalenderfreigabe ist kein Geräteimport möglich. Lokale Termine bleiben nutzbar.")
    }
    private val backup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> model.export(uri, false) } }
    private val ics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { it?.let { uri -> model.export(uri, true) } }
    private val eventImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.prepareImport(uri, true) } }
    private val holidayImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importHolidays) }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.prepareImport(uri) } }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectCalendar = savedInstanceState?.getBoolean("connectCalendar") ?: false
        enableEdgeToEdge()
        readTarget(intent, savedInstanceState?.getLong("launchToken"))
        if (savedInstanceState == null) receiveShare(intent) else savedInstanceState.getString("sharedFile")?.let { model.receiveSharedFile(Uri.parse(it)) }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.needsWritePermission) { if (state.needsWritePermission) writeCalendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
            GimboTheme(state.settings) {
              CompositionLocalProvider(LocalCategories provides state.categories, LocalCalendars provides (state.calendars + state.deviceSources.calendars)) {
                CalendarApp(state, model, target, permissionMessage, { permissionMessage = null },
                    onNotifications = { requestNotifications() }, onPrecise = { openPreciseSettings() },
                    onBackup = { backup.launch("Gimbo-Kalender-${LocalDate.now()}.json") },
                    onRestore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onIcs = { ics.launch("Gimbo-Kalender-Serien.ics") },
                    onWidget = { month -> pinWidget(month) },
                    onLanguage = { language ->
                        model.settings(state.settings.copy(language = language))
                        AppLocale.set(this, language)
                        if (Build.VERSION.SDK_INT < 33) recreate()
                    }, onContactBirthdays = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) model.importContactBirthdays()
                        else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }, onDeviceCalendars = { requestDeviceCalendars(false) }, onConnectDeviceCalendars = { requestDeviceCalendars(true) }, onEventImport = { eventImport.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) }, onHolidayImport = { holidayImport.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) })
              }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readTarget(intent); receiveShare(intent) }
    override fun onStart() { super.onStart(); (application as CalendarApplication).deviceConnections.foreground(true) }
    override fun onStop() { (application as CalendarApplication).deviceConnections.foreground(false); super.onStop() }
    override fun onResume() {
        super.onResume()
        model.refreshSettings()
        model.refreshDeviceSources()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ReminderScheduler.rebuild(this@MainActivity)
            de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(this@MainActivity)
            CalendarWidgets.update(this@MainActivity)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { model.state.value.sharedFile?.let { outState.putString("sharedFile", it.toString()) }; outState.putBoolean("connectCalendar", connectCalendar); outState.putLong("launchToken", target.token); super.onSaveInstanceState(outState) }
    private fun readTarget(intent: Intent, token: Long? = null) {
        target = LaunchTarget(runCatching { LocalDate.parse(intent.getStringExtra("date")) }.getOrDefault(LocalDate.now()),
            intent.getLongExtra("eventId", -1), intent.getBooleanExtra("add", false), token ?: System.nanoTime(),
            intent.hasExtra("date") || intent.hasExtra("eventId") || intent.hasExtra("add") || intent.hasExtra("page"), intent.getStringExtra("occurrenceKey"), intent.getStringExtra("page")?.takeIf { it == "tasks" })
    }
    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri?.scheme == "content") { model.receiveSharedFile(uri); target = LaunchTarget(add = true, navigate = true) }
        else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(10000)?.takeIf { it.isNotBlank() }?.let { text ->
            model.cancelSharedFile()
            target = LaunchTarget(add = true, navigate = true, sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(200) ?: text.lineSequence().first().take(200), sharedNotes = text)
        }
    }
    private fun requestDeviceCalendars(connect: Boolean) {
        connectCalendar = connect
        if (de.luma.calendar.integrations.DeviceCalendarSources.allowed(this)) model.loadDeviceCalendars(connect)
        else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !ReminderScheduler.notificationsAllowed(this)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (!ReminderScheduler.notificationsAllowed(this)) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun openPreciseSettings() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { permissionMessage = AppLocale.wrap(this).text("Bitte genaue Alarme in den Android-App-Einstellungen erlauben.") }
        } else permissionMessage = AppLocale.wrap(this).text("Genaue Erinnerungen sind bereits erlaubt.")
    }
    private fun pinWidget(month: Boolean) {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, if (month) MonthWidget::class.java else AgendaWidget::class.java), null, null)
        else permissionMessage = AppLocale.wrap(this).text("Homescreen lange berühren → Widgets → Gimbo Kalender.")
    }
}
