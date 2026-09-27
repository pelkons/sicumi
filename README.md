# Sicumi (סיכומי)

Мобильный ассистент **только на иврите** (RTL): протоколы встреч и голосовая диктовка в стиле Wispr Flow.
Сначала Android, iOS — после релиза Android.

- Спецификация: [docs/SPEC.md](docs/SPEC.md) (живая версия — в Claude Docs, ссылка в начале файла)
- Правила для AI-агентов: [CLAUDE.md](CLAUDE.md)
- Дизайн-токены: [shared/design/tokens.json](shared/design/tokens.json)

## Структура

```
android/   нативное приложение: Kotlin + Jetpack Compose
ios/       появится после релиза Android (SwiftUI)
shared/    общее для платформ: дизайн-токены, позже промпты и каталог провайдеров
docs/      спецификация
```

## Запуск Android

1. Открыть папку `android/` в Android Studio.
2. Дождаться Gradle Sync (скачается Gradle 9.4.1 и зависимости).
3. Запустить конфигурацию `app` на телефоне или эмуляторе.

Требования: JDK 17 (встроен в Android Studio), Android SDK с платформой API 37.
