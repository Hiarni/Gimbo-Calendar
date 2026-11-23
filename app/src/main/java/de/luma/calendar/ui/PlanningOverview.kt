package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import java.time.*

@Composable fun PlanningOverview(state: CalendarState, events: List<Occurrence>, open: (Occurrence) -> Unit, onDate: (LocalDate) -> Unit, onTasks: () -> Unit, onCategories: () -> Unit) {
    val t = texts()
    val today = LocalDate.now()
    val todayEvents = events.filter { overlapsDay(it, today) }
    val tasks = state.tasks.filter { !it.completed }
    val overdue = tasks.count { it.due?.let(LocalDate::parse)?.isBefore(today) == true }
    LazyColumn(Modifier.fillMaxSize().testTag("planning-overview"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(today.pretty(t.locale), style = MaterialTheme.typography.labelLarge)
                    Text(t("Deine Woche im Blick"), style = MaterialTheme.typography.headlineSmall)
                    Text(t("%1\$s Termine heute · %2\$s offene Aufgaben", todayEvents.size, tasks.size), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (overdue > 0) TextButton(onTasks) { Text(t("%1\$s Aufgaben überfällig", overdue)) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0L..6L).forEach { day ->
                            val date = today.plusDays(day)
                            val count = events.count { overlapsDay(it, date) }
                            Surface(onClick = { onDate(date) }, color = if (day == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, contentColor = if (day == 0L) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.widthIn(min = 48.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, t.locale), style = MaterialTheme.typography.labelSmall)
                                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                                    Text(if (count == 0) "·" else count.toString(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(t("Als Nächstes"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton({ onDate(today) }) { Text(t("Kalender")) } } }
        val upcoming = events.filter { it.end.toInstant() > Instant.now() }.take(5)
        if (upcoming.isEmpty()) item { Text(t("Platz für deine Pläne."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(upcoming, key = { it.key }) { value -> EventCard(value) { open(value) } }
        item {
            Surface(onClick = onTasks, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t("Aufgaben"), style = MaterialTheme.typography.titleMedium)
                        Text(t("%1\$s offen · %2\$s überfällig", tasks.size, overdue), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Right, null)
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(t("Kategorien"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onCategories) { Text(t("Verwalten")) } } }
        items(state.categories, key = { "category-${it.id}" }) { category ->
            val count = events.count { it.event.categoryId == category.id }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Color(category.color), CircleShape))
                    Text(category.name, Modifier.weight(1f).padding(start = 12.dp))
                    Text(t("%1\$s diese Woche", count), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.categories.isEmpty()) item { TextButton(onCategories) { Icon(Icons.Tag, null); Spacer(Modifier.width(8.dp)); Text(t("Kategorien einrichten")) } }
    }
}
