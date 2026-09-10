# Release validation — 2.0.3 preview

Дата: 10 сентября 2026.

## Изменения

- восстановление отпущенной кнопки S Pen без выхода из hover range;
- немедленное завершение временного инструмента при release во время контакта;
- сглаживание штрихов, pressure filtering и сохранение tilt;
- viewport culling и ограниченный LRU-кэш геометрии для больших документов.

## Автоматические ворота

Перед публикацией выполняются `testDebugUnitTest`, `lintDebug` и `assembleRelease`. Итоговый APK подписывается существующим preview-ключом и предназначен для `arm64-v8a`.

## Ограничения

- аппаратная проверка выполненной обработки кнопки требуется на целевом Galaxy Tab/S Pen;
- production Google Drive OAuth, WorkManager, remote apply/conflict UI и двухустройственная приёмка не входят в заявление готовности этого preview-релиза.
