# Как добавить Google Maps API ключ

## 1. Получи ключ
1. Зайди на https://console.cloud.google.com/
2. Создай проект или выбери существующий
3. APIs & Services → Enable APIs → **Maps SDK for Android**
4. APIs & Services → Credentials → Create Credentials → API Key
5. Скопируй ключ

## 2. Добавь в проект
Открой (или создай) файл `local.properties` в корне проекта и добавь строку:

```
MAPS_API_KEY=AIzaSy...твой_ключ_здесь
```

> ⚠️ Никогда не коммить `local.properties` в git — в нём секреты!
> Файл уже добавлен в `.gitignore`.

## 3. Ограничи ключ (важно!)
В Google Cloud Console:
- Application restrictions → Android apps
- Добавь: Package name = `com.aistudio.socialsphere.crmlxb`

## 4. Пересобери
В Android Studio: **Build → Clean Project → Run**
