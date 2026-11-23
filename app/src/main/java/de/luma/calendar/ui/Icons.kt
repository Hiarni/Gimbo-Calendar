package de.luma.calendar.ui

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

object Icons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = name in listOf("Zurück", "Vorheriger Zeitraum", "Nächster Zeitraum"))
        .addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val People by lazy { icon("Gemeinsam", "M14,7A3,3 0,1 1,8,7A3,3 0,1 1,14,7M4,21V18Q4,13 11,13Q18,13 18,18V21M17,4Q21,4 21,8Q21,11 18,11M20,14Q23,15 23,19V21") }
    val Sync by lazy { icon("Synchronisieren", "M4,10A8,8 0,0 1,18,6L21,9M21,4V9H16M20,14A8,8 0,0 1,6,18L3,15M3,20V15H8") }
    val Overview by lazy { icon("Übersicht", "M4,3H10V10H3V4Q3,3 4,3M15,3H20Q21,3 21,4V10H14V3ZM3,14H10V21H4Q3,21 3,20ZM14,14H21V20Q21,21 20,21H14Z") }
    val Tag by lazy { icon("Kategorie", "M3,4H12L22,14L14,22L3,11ZM7,7H7.1") }
    val Bell by lazy { icon("Erinnerung", "M5,17H19L17,14V9A5,5 0,0 0,7,9V14ZM10,21H14M11,2H13") }
    val Calendar by lazy { icon("Kalender", "M5,4H19Q21,4 21,6V20Q21,22 19,22H5Q3,22 3,20V6Q3,4 5,4M7,2V7M17,2V7M3,10H21M7,14H9M15,14H17M7,18H9") }
    val Agenda by lazy { icon("Agenda", "M8,6H21M8,12H21M8,18H16M3,6H3.1M3,12H3.1M3,18H3.1") }
    val Search by lazy { icon("Suche", "M17,10A7,7 0,1 1,3,10A7,7 0,1 1,17,10M15,15L21,21") }
    val Settings by lazy { icon("Einstellungen", "M10,2H14L15,5L18,6L21,5L23,9L21,12L23,15L21,19L18,18L15,19L14,22H10L9,19L6,18L3,19L1,15L3,12L1,9L3,5L6,6L9,5ZM16,12A4,4 0,1 1,8,12A4,4 0,1 1,16,12") }
    val More by lazy { icon("Mehr", "M13,5A1,1 0,1 1,11,5A1,1 0,1 1,13,5M13,12A1,1 0,1 1,11,12A1,1 0,1 1,13,12M13,19A1,1 0,1 1,11,19A1,1 0,1 1,13,19") }
    val Up by lazy { icon("Nach oben", "M6,15L12,9L18,15") }
    val Down by lazy { icon("Nach unten", "M6,9L12,15L18,9") }
    val Notes by lazy { icon("Notizblock", "M6,3H19Q21,3 21,5V19Q21,21 19,21H6Q4,21 4,19V5Q4,3 6,3M8,3V21M11,8H17M11,12H17M11,16H15M2,7H6M2,12H6M2,17H6") }
    val Pin by lazy { icon("Anheften", "M8,3H16L15,9L19,13H5L9,9ZM12,13V22") }
    val Archive by lazy { icon("Archiv", "M3,3H21V7H3ZM5,7V21H19V7M10,11H14") }
    val Share by lazy { icon("Teilen", "M6,13L18,6M6,13L18,20M8,13A3,3 0,1 1,2,13A3,3 0,1 1,8,13M22,5A3,3 0,1 1,16,5A3,3 0,1 1,22,5M22,20A3,3 0,1 1,16,20A3,3 0,1 1,22,20") }
    val Add by lazy { icon("Hinzufügen", "M12,5V19M5,12H19") }
    val Back by lazy { icon("Zurück", "M19,12H5M12,5L5,12L12,19") }
    val Left by lazy { icon("Vorheriger Zeitraum", "M15,6L9,12L15,18") }
    val Right by lazy { icon("Nächster Zeitraum", "M9,6L15,12L9,18") }
    val Close by lazy { icon("Schließen", "M6,6L18,18M18,6L6,18") }
    val Clock by lazy { icon("Zeit", "M22,12A10,10 0,1 1,2,12A10,10 0,1 1,22,12M12,6V12L16,15") }
    val Location by lazy { icon("Ort", "M12,22C8,17 4,13 4,9A8,8 0,1 1,20,9C20,13 16,17 12,22M15,9A3,3 0,1 1,9,9A3,3 0,1 1,15,9") }
    val Repeat by lazy { icon("Wiederholung", "M3,8H18L15,5M18,8L15,11M21,16H6L9,13M6,16L9,19") }
    val Delete by lazy { icon("Entfernen", "M3,6H21M9,6V3H15V6M5,6L6,21H18L19,6M10,10V17M14,10V17") }
    val Check by lazy { icon("Ausgewählt", "M4,12L9,17L20,6") }
}
