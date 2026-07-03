# Промпт: аудит кода SocialSphere — лучшие практики + безопасность

Ты — аудитор Android-приложения (Kotlin/Compose/Room, модуль `ss-v1/`). Личная CRM:
вся БД — PII (имена, телефоны, адреса, заметки, семья). Пройди чеклист ПОЛНОСТЬЮ,
каждое утверждение подтверждай grep'ом/чтением кода, не памятью. Результат — таблица:
Находка · Файл:строка · Серьёзность (🔴крит/🟡средн/🟢низк) · Фикс.

## A. Поверхность атаки (манифест и IPC)
1. `AndroidManifest.xml`: все `exported=true` оправданы? (Должны быть только launcher-activity
   и BootReceiver с protected-broadcast.) Нет ли intent-filter, открывающих deeplink на данные?
2. Разрешения: каждое `<uses-permission>` реально используется? Нет ли опасных лишних?
3. `FileProvider`: `grantUriPermissions`, пути в `file_paths.xml` — не шире необходимого
   (не корень filesDir/весь cache)?
4. `allowBackup=false` сохранён? `dataExtractionRules`/`fullBackupContent` не открывают БД?

## B. Данные и хранение
5. БД Room без шифрования — известный 🔴 (SQLCipher+Keystore в бэклоге). Не появилось ли
   НОВЫХ мест плоского хранения чувствительного: SharedPreferences с PII, файлы в
   `getExternalFilesDir`/`Environment.getExternal*`, MODE_WORLD_*?
6. Экспортные файлы (CSV/vCard/JSON/ZIP) — только в `cacheDir` + `cleanOldExports()`
   вызывается? TTL уборки адекватен? Не пишутся ли в Downloads без запроса?
7. Логи: `Log.d/i/w/e`, `println`, `printStackTrace` — нет PII (имена/телефоны/заметки)?
8. Буфер обмена: копируется ли чувствительное без нужды?

## C. Ввод/выход и инъекции
9. Room: только DAO/`@Query` с bind-параметрами? Нет `rawQuery`/конкатенации SQL?
10. Интенты наружу: `openWebsite`/`openMessenger`/ссылки из полей — white-list схем
    сохранился (`http/https/tg/viber/whatsapp/sgnl/mailto/tel`)? Новые места запуска
    Intent из пользовательских данных (link у подарка, website компании, messenger.link)
    проходят через безопасные хелперы, а не сырой `startActivity(Uri.parse(...))`?
11. Импорт (CSV/vCard/JSON): парсеры не падают на злом вводе (обрезка длин, лимит записей)?
    JSON-бэкап: version-check остался? Нет десериализации произвольных классов?
12. OCR/камера (новое): Bitmap не пишется на диск без нужды? tessdata пишется только в
    `filesDir`? Временные снимки не остаются в cache?
13. WebView отсутствует? (Если появился — стоп, отдельный аудит JS-interface/file access.)

## D. Сеть иsupply chain
14. `INTERNET` используется чем? (Карты + JitPack на сборке.) Нет `usesCleartextTraffic`?
    Все URL в коде/gradle — https?
15. Зависимости: новые с момента прошлого аудита (tesseract4android, CameraX, accompanist) —
    версии зафиксированы (не `+`), источники (mavenCentral/google/jitpack) — jitpack только
    для tesseract? Ключ Maps не в git (`local.properties`, secrets-plugin) и ограничен ли
    по package+SHA1 (консоль — вне кода, отметить как ручной пункт)?
16. Gradle-таска `downloadTessData`: https, фиксированный репозиторий, есть ли проверка
    целостности (пока нет — 🟡, добавить размер/سha256)?

## E. Криптография и приватность-функции
17. Нет самодельного крипто? (Если появится биометрия/шифрование — только Keystore/JCA.)
18. `privacyMode`/замок: защищённые заметки реально не рендерятся до снятия (не просто
    blur поверх текста, доступного скриншотом/доступностью)?

## F. Лучшие практики (корректность/устойчивость)
19. Compose: тяжёлое (`AppStateStore`-фильтрации) — в `remember/derivedStateOf`, не на каждом
    кадре? `LazyColumn` c `key=`? Нет `fillMaxSize` внутри скролл-Column (известный баг)?
20. Корутины: `Dispatchers.IO/Default` для диска/OCR; нет `GlobalScope`; отмена уважается?
21. Ресурсы: `use {}` на потоках; `Cursor.close()`; `TessBaseAPI.recycle()` в finally?
22. Крэши: `!!` (qa ловит), непустые `catch(e){}` глотающие ошибки там, где нужен фолбэк
    с сообщением; парсинг дат — через `parseFlexibleDate`, не голый `LocalDate.parse`.
23. Строки:новые ключи во ВСЕХ 3 локалях (ru/en/el); нет дубликатов (qa ловит).
24. Миграции Room: version++ при изменении Entity; экспорт схем в `app/schemas`.

## Порядок
1. Прогони `qa_check.py` (гейт).
2. Пройди A→F grep'ами (`exported`, `Log\.`, `rawQuery`, `startActivity`, `Uri.parse`,
   `getExternal`, `MODE_`, `GlobalScope`, `printStackTrace`, `http://`…).
3. Прочитай целиком: AndroidManifest, xml/file_paths, xml/backup_rules,
   xml/data_extraction_rules, ExternalActionHandler, ExportManager, build.gradle.kts.
4. Отчёт-таблица + сверка с прошлым аудитом (SOCIALSPHERE_KNOWLEDGE.md §20): что закрыто,
   что осталось (SQLCipher, Maps key restriction), что НОВОЕ.
