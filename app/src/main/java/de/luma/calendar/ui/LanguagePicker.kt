package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.luma.calendar.i18n.*

@Composable fun LanguagePicker(selected: AppLanguage, onChange: (AppLanguage) -> Unit) {
    val t = texts()
    var open by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(t(selected.label)) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(t("Sprache wählen")) }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text(t("Sprachen suchen")) }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(AppLanguage.entries.filter { search.isBlank() || it.label.contains(search, true) || it.tag.contains(search, true) || java.util.Locale.forLanguageTag(it.tag).getDisplayLanguage(t.locale).contains(search, true) }, key = { it.name }) { language ->
                    ListItem(headlineContent = { Text(t(language.label)) }, supportingContent = { if (language != AppLanguage.SYSTEM) Text(java.util.Locale.forLanguageTag(language.tag).getDisplayLanguage(t.locale)) }, trailingContent = {
                        RadioButton(selected == language, { open = false; search = ""; onChange(language) })
                    }, modifier = Modifier.fillMaxWidth().clickable { open = false; search = ""; onChange(language) })
                }
            }
        }
    }, confirmButton = { TextButton({ open = false }) { Text(t("Abbrechen")) } })
}
