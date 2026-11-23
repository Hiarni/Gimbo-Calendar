#!/usr/bin/env python3
from pathlib import Path
import argparse
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--clean', action='store_true')
args = parser.parse_args()

def clean_kotlin(text):
    result = []
    index = 0
    while index < len(text):
        if text.startswith('//', index):
            end = text.find('\n', index)
            index = len(text) if end < 0 else end
        elif text.startswith('/*', index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith('/*', index):
                    depth += 1; index += 2
                elif text.startswith('*/', index):
                    depth -= 1; index += 2
                else:
                    if text[index] == '\n': result.append('\n')
                    index += 1
            result.append(' ')
        elif text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = len(text) if end < 0 else end + 3
            result.append(text[index:end]); index = end
        elif text[index] in ('"', "'"):
            quote = text[index]
            start = index
            index += 1
            while index < len(text):
                if text[index] == '\\': index += 2
                elif text[index] == quote:
                    index += 1; break
                else: index += 1
            result.append(text[start:index])
        else:
            result.append(text[index]); index += 1
    return ''.join(result)

issues = []
for directory in ('app/src',):
    for path in (root / directory).rglob('*'):
        if path.suffix not in ('.kt', '.xml') or not path.is_file(): continue
        text = path.read_text(encoding='utf-8')
        cleaned = clean_kotlin(text) if path.suffix == '.kt' else re.sub(r'<!--.*?-->', '', text, flags=re.S)
        cleaned = '\n'.join(line.rstrip() for line in cleaned.split('\n'))
        cleaned = re.sub(r'\n[ \t]+\n', '\n\n', cleaned)
        cleaned = re.sub(r'\n{3,}', '\n\n', cleaned)
        if cleaned != text:
            if args.clean: path.write_text(cleaned, encoding='utf-8')
            else: issues.append(str(path.relative_to(root)) + ': comments or redundant whitespace')
resources = {}
for qualifier in ('values', 'values-de'):
    values = ET.parse(root / f'app/src/main/res/{qualifier}/strings.xml').getroot().findall('string')
    names = [value.attrib['name'] for value in values]
    if len(names) != len(set(names)): issues.append(qualifier + ': duplicate resource names')
    resources[qualifier] = {value.attrib['name']: value.text or '' for value in values}
if resources['values'].keys() != resources['values-de'].keys(): issues.append('DE/EN resource sets differ')
pattern = re.compile(r'%\d+\$[sd]|%%')
for key, text in resources['values'].items():
    if sorted(pattern.findall(text)) != sorted(pattern.findall(resources['values-de'].get(key, ''))): issues.append(key + ': format parameters differ')
source = (root / 'app/src/main/java/de/luma/calendar/i18n/LocalizedText.kt').read_text(encoding='utf-8')
for literal, name in re.findall(r'^\s*("(?:\\.|[^"\\])*")\s+to R\.string\.(\w+)', source, re.M):
    key = json.loads(literal.replace('\\$', '$'))
    german = resources['values-de'].get(name, '').replace("\\'", "'").replace('\\"', '"')
    if german != key: issues.append(name + ': German source key differs')
keys = {json.loads(literal.replace('\\$', '$')) for literal in re.findall(r'^\s*("(?:\\.|[^"\\])*")\s+to R\.string', source, re.M)}
for directory in ('ui', 'widget', 'reminders'):
    for path in (root / 'app/src/main/java/de/luma/calendar' / directory).rglob('*.kt'):
        for literal in re.findall(r'\b(?:t|text)\(("(?:\\.|[^"\\])*")', path.read_text(encoding='utf-8')):
            if '$' in literal.replace('\\$', ''): continue
            key = json.loads(literal.replace('\\$', '$'))
            if key not in keys: issues.append(str(path.relative_to(root)) + ': untranslated UI text: ' + key)
core_sources = ('Kalender', 'Agenda', 'Notizen', 'Gemeinsam', 'Monat', 'Woche', 'Tag', 'Heute', 'Übersicht', 'Einstellungen', 'Neuer Termin', 'Hinzufügen', 'Feiertag', 'Kategorien', 'Aufgaben', 'Notizblock', 'Neue Notiz', 'Checkliste', 'Speichern', 'Abbrechen', 'Suche')
source_names = {json.loads(literal.replace('\\$', '$')): name for literal, name in re.findall(r'^\s*("(?:\\.|[^"\\])*")\s+to R\.string\.(\w+)', source, re.M)}
locale_config = ET.parse(root / 'app/src/main/res/xml/locales_config.xml').getroot()
locale_tags = {item.attrib['{http://schemas.android.com/apk/res/android}name'] for item in locale_config}
for tag in locale_tags - {'de', 'en'}:
    path = root / 'app/src/main/res' / ('values-b+' + tag.replace('-', '+')) / 'strings.xml'
    if not path.exists(): issues.append(tag + ': missing language resources'); continue
    values = ET.parse(path).getroot().findall('string')
    names = [item.attrib['name'] for item in values]
    if len(names) != len(set(names)): issues.append(tag + ': duplicate resources')
    if not {source_names[text] for text in core_sources}.issubset(names): issues.append(tag + ': incomplete main navigation')
    for value in values:
        name = value.attrib['name']
        if name not in resources['values']: issues.append(tag + ': unknown resource: ' + name)
        elif sorted(pattern.findall(value.text or '')) != sorted(pattern.findall(resources['values'][name])): issues.append(tag + ': format parameters differ: ' + name)
if issues:
    raise SystemExit('\n'.join(issues))
print(f'Source style, complete DE/EN and main navigation for {len(locale_tags)} languages verified.')
