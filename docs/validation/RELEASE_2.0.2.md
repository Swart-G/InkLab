# Release validation — 2.0.2 preview

Дата: 10 сентября 2026.

## Выполненная локальная проверка

JDK: Eclipse Temurin 21.0.12.1.
Android SDK: `/home/rodip/Android/Sdk`.

Команда:

```bash
ANDROID_HOME=/home/rodip/Android/Sdk \
JAVA_HOME=/home/rodip/.cache/inklab-jdk-21 \
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Результат: passed после исправления вызовов `RenderCache` в его JVM-тесте.

## Ограничения релиза

- Инструментальные Android-тесты и аппаратная матрица из `IMPLEMENTATION_TASKS.md` не выполнялись в этом окружении.
- Облачный путь остаётся preview foundation: production OAuth gateway, WorkManager execution, remote apply/conflict UI и два устройства не приняты.
- Поэтому релиз помечается preview и не заявляет готовность cloud sync или полное закрытие QA-01.
