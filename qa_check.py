#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QA v2 для SocialSphere — статический ловец ошибок компиляции.
Кодирует уроки 31–43 из SOCIALSPHERE_RULES.md (Раздел 8).
Запуск: python3 qa_check.py  (из корня репо, рядом с ss-v1/)
Выход: код 0 = все проверки зелёные; 1 = есть нарушения.
"""
import os, re, sys

SRC = 'ss-v1/app/src/main/java'
errors = []

def kt_files():
    for root, _, files in os.walk(SRC):
        for fn in files:
            if fn.endswith('.kt'):
                yield os.path.join(root, fn)

for path in kt_files():
    s = open(path, encoding='utf-8').read()
    rel = path.split('java/')[-1]

    # У31: java.time.ChronoUnit не существует — только java.time.temporal.ChronoUnit
    for m in re.finditer(r'java\.time\.ChronoUnit', s):
        if 'java.time.temporal' not in s[max(0, m.start()-9):m.start()+5]:
            errors.append(f'{rel}: java.time.ChronoUnit -> нужен java.time.temporal.ChronoUnit')

    # У32: иконки без импорта (точечного или wildcard)
    pkg_map = {'Default': 'filled', 'Filled': 'filled', 'Outlined': 'outlined',
               'Rounded': 'rounded', 'Sharp': 'sharp'}
    for style, name in set(re.findall(r'Icons\.(Default|Filled|Outlined|Rounded|Sharp)\.(\w+)', s)):
        pkg = pkg_map[style]
        if (f'import androidx.compose.material.icons.{pkg}.*' not in s and
                f'import androidx.compose.material.icons.{pkg}.{name}\n' not in s):
            errors.append(f'{rel}: Icons.{style}.{name} без импорта {pkg}.*')
    # AutoMirrored иконки: свой пакет automirrored.<style>
    for style, name in set(re.findall(r'Icons\.AutoMirrored\.(Filled|Outlined|Rounded|Sharp)\.(\w+)', s)):
        amp = f'automirrored.{pkg_map.get(style, style.lower())}'
        if (f'import androidx.compose.material.icons.{amp}.*' not in s and
                f'import androidx.compose.material.icons.{amp}.{name}\n' not in s):
            errors.append(f'{rel}: Icons.AutoMirrored.{style}.{name} без импорта {amp}.{name}')

    # У33: FlowRow/FlowColumn требуют @file:OptIn(ExperimentalLayoutApi)
    if re.search(r'\bFlowRow\b|\bFlowColumn\b', s):
        if '@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi' not in s:
            errors.append(f'{rel}: FlowRow без @file:OptIn(ExperimentalLayoutApi) — у K2 это ОШИБКА')

    # У34: PaddingValues — vertical/horizontal нельзя смешивать со сторонами
    for m in re.finditer(r'PaddingValues\(([^)]*)\)', s):
        args = m.group(1)
        axis  = ('vertical' in args) or ('horizontal' in args)
        sides = re.search(r'\b(top|bottom|start|end)\s*=', args)
        if axis and sides:
            errors.append(f'{rel}: PaddingValues смешивает оси и стороны: ({args.strip()[:60]})')

    # У35: сравнение LocalDate через < > — K2 спотыкается; только isBefore/isAfter
    for m in re.finditer(r'\b(\w*[Dd]ate|thisYear|today|next)\w*\s*[<>]=?\s*(\w*[Dd]ate|today|thisYear|next)\b', s):
        line = s[:m.start()].count('\n') + 1
        frag = m.group(0)
        if 'String' in frag or '"' in frag:
            continue
        errors.append(f'{rel}:{line}: подозрение на LocalDate <> ({frag}) — используй isBefore/isAfter')

    # У36: унарный минус на потенциально nullable (-имя после null-проверки в when)
    for m in re.finditer(r'\$\{-(\w+)\}', s):
        var = m.group(1)
        if re.search(rf'val {var}\s*=\s*try', s) or re.search(rf'{var}\s*!=\s*null', s):
            if not re.search(rf'val \w+:\s*(Long|Int)\s*=\s*{var}', s):
                errors.append(f'{rel}: ${{-{var}}} на nullable — сделай val x: Long = {var} перед when')

    # У37: запрещённые паттерны проекта (база)
    # AutoMirrored запрещён КРОМЕ реально зеркалящихся иконок (стрелки навигации):
    # на BOM 2024.09.03 обычный ArrowBack deprecated, AutoMirrored — правильный выбор (урок 47)
    AUTOMIRROR_OK = ('ArrowBack', 'ArrowForward', 'ArrowLeft', 'ArrowRight',
                     'ExitToApp', 'Login', 'Logout', 'Send', 'List', 'Help', 'Sort',
                     'Undo', 'Redo', 'Reply', 'ReplyAll', 'Comment', 'Chat', 'Message')
    for m in re.finditer(r'Icons\.AutoMirrored\.\w+\.(\w+)', s):
        if m.group(1) not in AUTOMIRROR_OK:
            errors.append(f'{rel}: Icons.AutoMirrored.{m.group(1)} — AutoMirrored только для зеркальных иконок')
    if 'resolveActivity' in s:
        errors.append(f'{rel}: resolveActivity запрещён (Android 11+)')
    if re.search(r'database!!', s):
        errors.append(f'{rel}: database!! запрещён')
    for m in re.finditer(r'(\w)!!(?!=)', s):
        line = s[:m.start()].count('\n') + 1
        errors.append(f'{rel}:{line}: force unwrap !! запрещён')

    # У38: дубли импортов
    imports = [l for l in s.splitlines() if l.startswith('import ')]
    for dup in set(i for i in imports if imports.count(i) > 1):
        errors.append(f'{rel}: дубль импорта {dup}')

    # У39: задвоенные label-return (мусор от правок)
    if re.search(r'return@\w+\s*:\s*return@', s):
        errors.append(f'{rel}: задвоенный return@label — синтаксическая ошибка')

    # У40: баланс скобок (грубая проверка после скриптовых правок)
    if s.count('{') != s.count('}'):
        errors.append(f'{rel}: дисбаланс {{}} = {s.count("{") - s.count("}")}')

# У51: каждый компонент из манифеста (receiver/activity/service/provider)
# должен существовать как класс в исходниках — иначе ClassNotFoundException
# в рантайме (R8 удалил, опечатка в манифесте, package/folder mismatch)
MANIFEST = 'ss-v1/app/src/main/AndroidManifest.xml'
if os.path.exists(MANIFEST):
    mani = open(MANIFEST, encoding='utf-8').read()
    ns_match = re.search(r'package="([\w.]+)"', mani)
    # namespace берём из build.gradle (manifest без package в AGP 8)
    bg = 'ss-v1/app/build.gradle.kts'
    pkg = None
    if os.path.exists(bg):
        m = re.search(r'namespace\s*=\s*"([\w.]+)"', open(bg, encoding='utf-8').read())
        if m: pkg = m.group(1)
    if pkg:
        for comp in re.finditer(r'android:name="(\.[\w.]+|[\w.]+)"', mani):
            name = comp.group(1)
            # только компоненты с относительным/полным именем класса приложения
            fqcn = pkg + name if name.startswith('.') else name
            if not fqcn.startswith(pkg):
                continue
            rel_path = 'ss-v1/app/src/main/java/' + fqcn.replace('.', '/') + '.kt'
            if not os.path.exists(rel_path):
                errors.append(f'{MANIFEST}: компонент {name} -> класс не найден ({rel_path}) — ClassNotFoundException')

# У52: при isMinifyEnabled=true манифестные компоненты должны быть в -keep,
# иначе R8 вырежет их из release-dex (ClassNotFoundException на boot)
bg = 'ss-v1/app/build.gradle.kts'
pg = 'ss-v1/app/proguard-rules.pro'
if os.path.exists(bg) and 'isMinifyEnabled = true' in open(bg, encoding='utf-8').read():
    rules = open(pg, encoding='utf-8').read() if os.path.exists(pg) else ''
    if 'extends android.content.BroadcastReceiver' not in rules:
        errors.append(f'{pg}: minify включён, но нет -keep для BroadcastReceiver — R8 вырежет ресиверы из release')

# У50: дубликаты <string name="..."> внутри одного strings.xml — AAPT error
for path in ['ss-v1/app/src/main/res/values/strings.xml',
              'ss-v1/app/src/main/res/values-en/strings.xml',
              'ss-v1/app/src/main/res/values-el/strings.xml']:
    if not os.path.exists(path):
        continue
    names = re.findall(r'<string name="(\w+)"', open(path, encoding='utf-8').read())
    dups = sorted(set(n for n in names if names.count(n) > 1))
    if dups:
        errors.append(f'{path}: дубликаты ключей strings.xml: {dups}')

# У49: ЛОКАЛИЗОВАННЫЕ экраны — ноль кириллицы в строковых литералах
# (пополняется по мере прохождения серии локализации)
LOCALIZED_FILES = ['HomeScreen.kt', 'ContactsScreen.kt', 'ContactDetailScreen.kt', 'CalendarScreen.kt', 'MapScreen.kt',
                   'SettingsScreen.kt', 'AppearanceSettingsScreen.kt', 'LanguageSettingsScreen.kt',
                   'NotificationSettingsScreen.kt', 'PrivacySettingsScreen.kt',
                   'CalendarSettingsScreen.kt', 'ImportExportSettingsScreen.kt',
                   'CompaniesScreen.kt', 'CompanyDetailScreen.kt', 'ContactEditScreen.kt', 'CompanyEditScreen.kt', 'CalendarItemDetailScreen.kt',
                   'CalendarItemEditScreen.kt', 'CheatSheetScreen.kt', 'ImportScreens.kt']
ROLE_DATA_OK = {'Муж','Жена','Партнёр','Отец','Мать','Брат','Сестра','Сын','Дочь','Родственник','Друг','Коллега'}
for path in kt_files():
    fn = os.path.basename(path)
    if fn not in LOCALIZED_FILES:
        continue
    for i, line in enumerate(open(path, encoding='utf-8').read().splitlines(), 1):
        code = line.split('//')[0]  # комментарии не считаем
        for m in re.finditer(r'"([^"]*)"', code):
            if re.search(r'[А-Яа-яЁё]', m.group(1)) and m.group(1) not in ROLE_DATA_OK:
                errors.append(f'{fn}:{i}: кириллица в локализованном экране: "{m.group(1)[:40]}"')

# У41: вызовы Объект.функция, где функция — top-level (известный список)
imp_s = open(os.path.join(SRC, 'com/aistudio/socialsphere/crmlxb/utils/ContactImporter.kt'),
             encoding='utf-8').read()
toplevel = re.findall(r'^(?:internal )?fun (\w+)\(', imp_s, re.M)
for path in kt_files():
    s = open(path, encoding='utf-8').read()
    for fn in toplevel:
        if f'ContactImporter.{fn}(' in s:
            errors.append(f'{path.split("java/")[-1]}: ContactImporter.{fn}() — '
                          f'{fn} top-level, вызывай без квалификатора + импорт')

# У42: enum-значения, использованные в UI-файлах, существуют в Enums.kt
enums_s = open(os.path.join(SRC, 'com/aistudio/socialsphere/crmlxb/model/Enums.kt'),
               encoding='utf-8').read()
known = {}
for em in re.finditer(r'enum class (\w+)\s*\{([^}]*)\}', enums_s):
    known[em.group(1)] = set(re.findall(r'\b[A-Z][A-Z_0-9]+\b', em.group(2)))
for path in kt_files():
    s = open(path, encoding='utf-8').read()
    for ename, values in known.items():
        for m in re.finditer(rf'\b{ename}\.([A-Z][A-Z_0-9]+)\b', s):
            if m.group(1) not in values and m.group(1) != 'values'.upper():
                errors.append(f'{path.split("java/")[-1]}: {ename}.{m.group(1)} не существует '
                              f'(есть: {", ".join(sorted(values)[:6])}...)')

print(f'QA v2: проверено файлов: {sum(1 for _ in kt_files())}')
if errors:
    print(f'\n❌ НАРУШЕНИЙ: {len(errors)}')
    for e in errors[:40]:
        print('  ' + e)
    sys.exit(1)
print('✅ Все проверки зелёные')
