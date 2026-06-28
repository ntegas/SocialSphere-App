#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QA v2 для SocialSphere — статический ловец ошибок компиляции.
Кодирует уроки 31–54 (см. SOCIALSPHERE_KNOWLEDGE.md, разделы 5 и 17).
Запуск: PYTHONIOENCODING=utf-8 python qa_check.py  (из корня репо, рядом с ss-v1/)
Выход: код 0 = все проверки зелёные; 1 = есть нарушения.
"""
import os, re, sys

SRC = 'ss-v1/app/src/main/java'
errors = []

# У57: не-иконочный символ используется без импорта (обобщение У32).
# Класс ошибки «token-swap без импорта»: Apple-миграция меняла colorScheme→AppleTheme
# и вставляла Color(...) без import — qa был зелёным, а compileDebugKotlin падал на
# Unresolved reference. Исключения: полное имя (a.b.Color), wildcard-импорт,
# само-определение символа в файле.
def symbol_needs_import(s, use_re, imp, wilds, defmark):
    if defmark and re.search(defmark, s):
        return False
    if not re.search(use_re, s):
        return False           # либо не используется, либо только как полное имя
    if imp in s:
        return False
    return not any(w in s for w in wilds)

SYMBOL_IMPORTS = [
    ('AppleTheme', r'(?<![\w.])AppleTheme\.',
     'import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme',
     ['import com.aistudio.socialsphere.crmlxb.ui.theme.*'],
     r'object AppleTheme\b'),
    ('Color', r'(?<![\w.])Color[(.]',
     'import androidx.compose.ui.graphics.Color',
     ['import androidx.compose.ui.graphics.*'],
     r'(?:class|object|typealias) Color\b'),
]
# self-tests У57
_at = SYMBOL_IMPORTS[0][1:]
assert symbol_needs_import('val x = AppleTheme.colors.brand', *_at) is True, 'У57 neg'
assert symbol_needs_import('import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme\nAppleTheme.colors', *_at) is False, 'У57 import'
assert symbol_needs_import('object AppleTheme {}\nAppleTheme.colors', *_at) is False, 'У57 self-def'
assert symbol_needs_import('NavigationBar(x = com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.card)', *_at) is False, 'У57 fqcn'
_co = SYMBOL_IMPORTS[1][1:]
assert symbol_needs_import('Box(Modifier.background(Color(0xFF112233)))', *_co) is True, 'У57 color neg'
assert symbol_needs_import('val c = androidx.compose.ui.graphics.Color.Transparent', *_co) is False, 'У57 color fqcn'
assert symbol_needs_import('NavigationBar(containerColor = AppleTheme.colors.card)', *_co) is False, 'У57 color suffix'

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
                     'Undo', 'Redo', 'Reply', 'ReplyAll', 'Comment', 'Chat', 'Message',
                     # BOM 2024.09.03 пометил эти как deprecated в пользу AutoMirrored
                     # (направление-зависимые, зеркалятся в RTL) — урок 47
                     'FormatListBulleted', 'Label', 'MergeType', 'Notes')
    for m in re.finditer(r'Icons\.AutoMirrored\.\w+\.(\w+)', s):
        if m.group(1) not in AUTOMIRROR_OK:
            errors.append(f'{rel}: Icons.AutoMirrored.{m.group(1)} — AutoMirrored только для зеркальных иконок')
    if 'resolveActivity' in s:
        errors.append(f'{rel}: resolveActivity запрещён (Android 11+)')
    if re.search(r'database!!', s):
        errors.append(f'{rel}: database!! запрещён')
    # Ловим force-unwrap после слова И после закрывающих скобок: }!! )!! ]!!
    for m in re.finditer(r'([\w)\]}])!!(?!=)', s):
        line = s[:m.start()].count('\n') + 1
        errors.append(f'{rel}:{line}: force unwrap !! запрещён')

    # У57: не-иконочный символ без импорта (AppleTheme/Color и т.п.)
    for name, use_re, imp, wilds, defmark in SYMBOL_IMPORTS:
        if symbol_needs_import(s, use_re, imp, wilds, defmark):
            errors.append(f'{rel}: {name} используется без импорта ({imp})')

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

# У53: все .webp-ресурсы целы (ненулевой размер + сигнатура RIFF/WEBP).
# Ловит битьё иконки при упаковке ZIP. NB: «does not exist / Access is denied»
# при сборке на Windows из Downloads — это OneDrive Files-On-Demand или Defender,
# НЕ наша проблема (файл в ZIP валиден); лечится переносом проекта в C:\dev\.
RES_DIR = 'ss-v1/app/src/main/res'
if os.path.isdir(RES_DIR):
    for root, _, fnames in os.walk(RES_DIR):
        for fn in fnames:
            if fn.endswith('.webp'):
                p = os.path.join(root, fn)
                size = os.path.getsize(p)
                if size == 0:
                    errors.append(f'{p}: webp нулевого размера — битый ресурс')
                    continue
                with open(p, 'rb') as fh:
                    head = fh.read(16)
                if not (head[:4] == b'RIFF' and b'WEBP' in head):
                    errors.append(f'{p}: webp без сигнатуры RIFF/WEBP — битый ресурс')

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

    # У54: незаэкранированные ' и & в значениях <string> — AAPT2 роняет
    # mergeDebugResources с «Invalid unicode escape sequence / not a valid string».
    # Апостроф обязан быть \' (или строка в "кавычках"); & обязан быть &amp; и т.п.
    raw = open(path, encoding='utf-8').read()
    for m in re.finditer(r'<string\b[^>]*>(.*?)</string>', raw, re.S):
        name_m = re.search(r'name="(\w+)"', m.group(0))
        nm = name_m.group(1) if name_m else '?'
        val = m.group(1)
        quoted = val.strip().startswith('"') and val.strip().endswith('"')
        if not quoted and re.search(r"(?<!\\)'", val):
            errors.append(f'{path}: string/{nm} — незаэкранированный апостроф, нужен \\\'')
        if re.search(r'&(?!amp;|lt;|gt;|quot;|apos;|#)', val):
            errors.append(f'{path}: string/{nm} — незаэкранированный &, нужен &amp;')

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

# У55: @Database(version = N) ⇒ в addMigrations(...) есть полная цепочка
# MIGRATION_1_2 … MIGRATION_(N-1)_N. Ловит «голый» бамп версии без миграции
# (как был v5→v6): без миграции срабатывает destructive fallback = потеря данных.
def missing_migrations(src):
    """Возвращает список недостающих MIGRATION_x_(x+1) для объявленной version."""
    vm = re.search(r'version\s*=\s*(\d+)', src)
    if not vm:
        return []
    version = int(vm.group(1))
    am = re.search(r'addMigrations\(([^)]*)\)', src)
    registered = set(re.findall(r'MIGRATION_(\d+)_(\d+)', am.group(1))) if am else set()
    missing = []
    for v in range(1, version):
        if (str(v), str(v + 1)) not in registered:
            missing.append(f'MIGRATION_{v}_{v + 1}')
    return missing

# негативный тест: версия 6, но цепочка обрывается на 4_5 — гард ОБЯЗАН поймать
_neg = '''@Database(version = 6) .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)'''
assert missing_migrations(_neg) == ['MIGRATION_5_6'], 'У55 self-test провален'
# позитивный тест: полная цепочка — пусто
_pos = '''@Database(version = 6) .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)'''
assert missing_migrations(_pos) == [], 'У55 self-test (позитив) провален'

DB_FILE = os.path.join(SRC, 'com/aistudio/socialsphere/crmlxb/data/local/SocialsphereDatabase.kt')
if os.path.exists(DB_FILE):
    miss = missing_migrations(open(DB_FILE, encoding='utf-8').read())
    if miss:
        errors.append(f'SocialsphereDatabase.kt: version поднят без миграций {miss} '
                      f'— апдейт «поверх» сотрёт БД (destructive fallback)')

# У56: `private fun` — область ОДНОГО файла. Если функцию вызывают из другого
# файла того же пакета (как ActionSquare/GiftMenu после выноса вкладок), K2 даёт
# "Cannot access … it is private in file". Ловим до сборки: для каждого
# `private fun NAME` проверяем, не зовут ли NAME( из другого файла, который сам
# NAME не определяет.
def cross_file_private(files):
    """files: dict {path: source}. Возвращает [(name, def_file, [caller_files])]."""
    defs = {}          # name -> file где есть private fun
    defines_any = {}   # name -> set(files где есть любой fun NAME / val NAME)
    for f, src in files.items():
        for m in re.finditer(r'private fun (?:[\w.]+\.)?(\w+)\s*\(', src):
            defs.setdefault(m.group(1), f)
        for m in re.finditer(r'\bfun (?:[\w.]+\.)?(\w+)\s*\(|\bval (\w+)\b', src):
            nm = m.group(1) or m.group(2)
            defines_any.setdefault(nm, set()).add(f)
    out = []
    for name, deffile in defs.items():
        callers = []
        for f, src in files.items():
            if f == deffile:
                continue
            if f in defines_any.get(name, set()):
                continue  # этот файл объявляет свой NAME — не cross-file
            if re.search(rf'(?<![\w.]){name}\s*\(', src):
                callers.append(f)
        if callers:
            out.append((name, deffile, callers))
    return out

# self-test
_neg_files = {'A.kt': 'private fun ActionSquare() {}', 'B.kt': 'fun x() { ActionSquare() }'}
assert cross_file_private(_neg_files) == [('ActionSquare', 'A.kt', ['B.kt'])], 'У56 neg self-test провален'
_pos_files = {'A.kt': 'internal fun ActionSquare() {}', 'B.kt': 'fun x() { ActionSquare() }'}
assert cross_file_private(_pos_files) == [], 'У56 pos self-test провален'
_own_files = {'A.kt': 'private fun Foo() {}', 'B.kt': 'private fun Foo() {}\nfun x(){Foo()}'}
assert cross_file_private(_own_files) == [], 'У56 own-def self-test провален'

SCREENS_DIR = os.path.join(SRC, 'com/aistudio/socialsphere/crmlxb/ui/screens')
if os.path.isdir(SCREENS_DIR):
    sfiles = {fn: open(os.path.join(SCREENS_DIR, fn), encoding='utf-8').read()
              for fn in os.listdir(SCREENS_DIR) if fn.endswith('.kt')}
    for name, deffile, callers in cross_file_private(sfiles):
        errors.append(f'{deffile}: private fun {name} зовётся из {callers} — '
                      f'смени на internal (K2: "it is private in file")')

print(f'QA v2: проверено файлов: {sum(1 for _ in kt_files())}')
if errors:
    print(f'\n❌ НАРУШЕНИЙ: {len(errors)}')
    for e in errors[:40]:
        print('  ' + e)
    sys.exit(1)
print('✅ Все проверки зелёные')
