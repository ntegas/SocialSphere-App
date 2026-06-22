# Карта Google Maps — настроить, чтобы работала, и подготовить к стору

> Код карты в приложении уже подключён правильно (зависимости, secrets-плагин,
> плейсхолдер `${MAPS_API_KEY}` в манифесте). Если карта серая/пустая — дело
> почти всегда в ключе. 4 частые причины: **(1) не включён биллинг,
> (2) не включён Maps SDK for Android, (3) ключ ограничен без SHA-1,
> (4) SHA-1 от другого keystore.** Ниже по шагам.

---

## Шаг 1. Биллинг (обязательно, но в рамках бесплатного лимита)
Google Maps SDK требует привязанный платёжный аккаунт, даже если ты в пределах
бесплатного объёма.
1. https://console.cloud.google.com/ → выбери/создай проект.
2. Billing → Link a billing account (привяжи карту). Без этого карта = серый фон.

## Шаг 2. Включить нужный API
APIs & Services → **Enable APIs and Services** → найди и включи
**Maps SDK for Android**. (Именно Android, не JavaScript/Embed.)

## Шаг 3. Создать ключ
APIs & Services → **Credentials** → Create credentials → **API key**. Скопируй.

## Шаг 4. Узнать SHA-1 (это и есть главная причина пустой карты)
Ключ для Android авторизует приложение по паре **package name + SHA-1**.
Без правильного SHA-1 карта не загрузится.

**Debug SHA-1 (для разработки):**
```
cd путь_к_проекту/ss-v1
gradlew signingReport          # Windows
./gradlew signingReport        # Mac/Linux
```
Найди в выводе блок `Variant: debug` → строку `SHA1:` → скопируй (вид `AB:CD:...`).

**Release SHA-1 (для публикации):** если используешь Play App Signing (рекомендуется),
бери его в Google Play Console → твоё приложение → Setup → **App integrity** →
App signing key certificate → **SHA-1**.

## Шаг 5. Ограничить ключ
Credentials → твой ключ:
- **Application restrictions** → Android apps → Add:
  - Package name: `com.aistudio.socialsphere.crmlxb`
  - SHA-1: вставь **debug SHA-1** (и отдельной строкой **release SHA-1** для стора).
- **API restrictions** → Restrict key → отметь **Maps SDK for Android**.
- Save. Изменения вступают в силу до ~5 минут.

## Шаг 6. Положить ключ в проект
В корне модуля (`ss-v1/`) открой/создай `local.properties` и добавь:
```
MAPS_API_KEY=AIzaSy...твой_ключ
```
> ⚠️ `local.properties` НЕ коммить в git (уже в .gitignore) — это секрет.
> Файл `local.properties.example` нужен secrets-плагину, его оставляем.

## Шаг 7. Ротировать утёкший ключ (важно!)
Старый ключ светился в CI-файле/истории. В Credentials:
- Либо **Regenerate key**, либо создай новый и удали старый.
- Новый ключ — только в `local.properties` (локально) и в GitHub → Settings →
  Secrets → Actions → `MAPS_API_KEY` (для CI). Никогда не вписывай ключ в `.yml`.

## Шаг 8. Чистая пересборка
Распакуй в короткий ASCII-путь (`C:\ss\`), затем:
```
gradlew clean
gradlew --no-build-cache assembleDebug
```
Установи **поверх** старого приложения (не удаляя — иначе сотрёшь данные).

---

## Если карта всё ещё серая — чек-лист
- [ ] Биллинг привязан к проекту?
- [ ] Включён именно **Maps SDK for Android**?
- [ ] В ограничении ключа добавлен **package name + SHA-1**?
- [ ] SHA-1 совпадает с тем keystore, которым собрана текущая сборка
      (debug-сборка → debug SHA-1)?
- [ ] Прошло несколько минут после сохранения ограничений?
- [ ] `MAPS_API_KEY` реально попал в сборку (после `gradlew clean`)?
- [ ] Logcat: фильтр по `Google Maps` / `Authorization failure` — там пишет
      причину (часто прямым текстом: "ensure ... API key ... SHA-1 ... package").

## Для публикации в Play Store
- В ограничение ключа добавь **release SHA-1** (из Play App Signing).
- Можно держать **два** ключа: один debug (твой SHA-1), один release
  (Play-signing SHA-1) — или один ключ с обоими SHA-1.
- Перед релизом проверь, что ключ ограничен по package + SHA-1 и по
  **Maps SDK for Android** (не «без ограничений» — иначе риск кражи квоты).
