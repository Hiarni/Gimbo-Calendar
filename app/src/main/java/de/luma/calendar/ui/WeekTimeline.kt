package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import kotlin.math.roundToInt
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun WeekTimeline(start: LocalDate, occurrences: List<Occurrence>, onOpen: (Occurrence) -> Unit, onAdd: (LocalDate, Int) -> Unit,
    onDay: (LocalDate) -> Unit, onMove: (Occurrence, LocalDateTime) -> Unit) {
    val t = texts(); val calendars = LocalCalendars.current
    val hourHeight = 72.dp; val density = LocalDensity.current; val pixelsPerMinute = with(density) { hourHeight.toPx() } / 60
    val days = (0L..6).map(start::plusDays)
    val allDay = days.associateWith { day -> occurrences.filter { it.event.allDay && overlapsDay(it, day) } }
    val allDayHeight = if (allDay.values.any { it.isNotEmpty() }) 48.dp else 0.dp
    var help by remember { mutableStateOf(false) }
    Column {
        Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).testTag("week-timeline")) {
            Row {
                TextButton({ help = true }, Modifier.width(44.dp).height(48.dp).semantics { contentDescription = t("Wochenhilfe") }) { Text("?") }
                days.forEach { day -> Column(Modifier.width(156.dp).padding(4.dp)) {
                    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEE d MMM", t.locale)), Modifier.weight(1f).clickable { onDay(day) }, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        IconButton({ onAdd(day, 9) }) { Icon(Icons.Add, t("Termin am %1\$s hinzufügen", day.pretty(t.locale))) }
                    }
                    if (allDayHeight > 0.dp) Box(Modifier.height(allDayHeight)) { allDay[day].orEmpty().firstOrNull()?.let { o ->
                        TextButton({ onDay(day) }, Modifier.fillMaxWidth().height(48.dp)) { Text(o.event.title + if (allDay[day]!!.size > 1) " +${allDay[day]!!.size - 1}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } }

                } }
            }
            Row(Modifier.verticalScroll(rememberScrollState(with(density) { (7 * hourHeight.value).dp.roundToPx() }))) {
                Column(Modifier.width(44.dp)) { (0..23).forEach { hour -> Text("%02d:00".format(hour), Modifier.height(hourHeight), style = MaterialTheme.typography.labelSmall) } }
                days.forEach { day ->
                    val blocks = remember(occurrences, day) { TimelineLayout.blocks(occurrences, day) }
                    Box(Modifier.width(156.dp).height(hourHeight * 24).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        (0..23).forEach { hour -> Box(Modifier.offset(y = hourHeight * hour).fillMaxWidth().height(hourHeight).clickable(onClickLabel = t("Termin am %1\$s hinzufügen", day.pretty(t.locale)), onClick = { onAdd(day, hour) }).border(.5.dp, MaterialTheme.colorScheme.outlineVariant)) }
                        blocks.forEach { block ->
                            var dragged by remember(block.occurrence.key) { mutableFloatStateOf(0f) }
                            val width = 156.dp / block.lanes
                            val color = Color(block.occurrence.event.calendarColor(calendars))
                            Surface(onClick = { onOpen(block.occurrence) }, color = color, contentColor = if (color.luminance() > .45f) Color(0xFF201A35) else Color.White,
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.offset { IntOffset(with(density) { (width * block.lane).roundToPx() }, with(density) { (hourHeight * (block.startMinute / 60f)).roundToPx() } + dragged.roundToInt()) }
                                    .width(width).height(maxOf(48.dp, hourHeight * ((block.endMinute - block.startMinute) / 60f))).padding(1.dp)
                                    .testTag("week-event-${block.occurrence.key}")
                                    .then(if (block.occurrence.event.id > 0) Modifier.pointerInput(block.occurrence.key) { detectDragGesturesAfterLongPress(onDrag = { change, amount -> change.consume(); dragged += amount.y },
                                        onDragCancel = { dragged = 0f }, onDragEnd = {
                                            if (block.occurrence.event.id > 0 && kotlin.math.abs(dragged) > pixelsPerMinute * 7.5f) onMove(block.occurrence, day.atStartOfDay().plusMinutes(TimelineLayout.snappedMinute(block.startMinute + dragged / pixelsPerMinute).toLong()))
                                            dragged = 0f
                                        }) } else Modifier)) {
                                Column(Modifier.padding(6.dp)) { Text(block.occurrence.event.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                    Text(block.occurrence.timeLabel(t), style = MaterialTheme.typography.labelSmall)
                                    if (block.conflict) Text(t("Überschneidung"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(t("Woche")) }, text = { Text(t("Zeitplan · lange drücken und ziehen verschiebt in 15-Minuten-Schritten. Vor dem Speichern öffnet sich der Termin.")) }, confirmButton = { TextButton({ help = false }) { Text(t("Verstanden")) } })

}
