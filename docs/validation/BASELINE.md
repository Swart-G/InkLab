# Baseline локального хранения и истории

Дата фиксации: 8 сентября 2026.
Исходный commit для сравнения: `c9db8fb50771c724ae86524a572f70a09099a85e`.
Ветка реализации: `storage-foundation`, PR #5.

Этот файл фиксирует исходную точку и результат этапа `BASE-01 / DATA-01`. Числа, требующие физического Android-планшета, намеренно не выдумываются: они добавляются только после аппаратного прогона с указанием устройства и условий.

## 1. Исходное состояние на c9db8fb

| Область | Состояние | Риск |
|---|---|---|
| Документы | отдельные `documents/<id>.json` через `AtomicFile` | отдельный документ атомарен, библиотека целиком — нет |
| Индекс | `documents/index.json` публикуется отдельно | process death между payload и index может оставить несогласованное состояние |
| Папки | `folders.json` сохраняется отдельным вызовом | документ и структура папок не имеют общей commit boundary |
| Autosave | `Channel.CONFLATED` со снимком документов и папок | нет durable revision |
| Статус save | любой завершившийся save выставляет `saving=false` | старый ack может скрыть новую ожидающую правку |
| Undo/Redo | `ArrayDeque<DocumentSnapshot>` с полным `InkBoard` | стоимость истории растёт с размером всего документа |
| Лимит history | 50 snapshots | лимит по количеству, а не по памяти |
| Схема | writer пишет `schemaVersion=2` | baseline-reader не запрещал будущую неизвестную схему |
| CI | JVM tests + lint + release build | androidTest даже не компилировался workflow |

## 2. Реализованная storage foundation

### Durable journal

Каждая логическая мутация библиотеки получает монотонный `journalSequence`. После первого checkpoint обычное сохранение записывает только delta: изменённые документы, удаления, порядок документов и при необходимости папки. Запись считается подтверждённой только после `FileDescriptor.sync()`.

Journal использует NDJSON-записи с SHA-256. Один оборванный хвост после process death игнорируется как неподтверждённый. Повреждение записи не в хвосте считается блокирующей ошибкой: подтверждённые операции нельзя молча пропускать.

### Checkpoint

Checkpoint — immutable generation с manifest и A/B payload slots. `CURRENT` переключается только после записи и проверки manifest/payload. Физический `generationId` отделён от причинного `journalSequence`, поэтому заброшенный или повреждённый checkpoint не ломает нумерацию пользовательских сохранений.

После нового checkpoint journal компактизируется только через **предыдущий** checkpoint. Поэтому при повреждении newest generation loader может откатиться на предыдущую и повторно проиграть сохранённый участок journal до самой новой подтверждённой версии.

Checkpoint создаётся при первом сохранении и далее после накопления журнала; `BoardRepository.checkpoint()` позволяет принудительно зафиксировать текущее состояние без новой пользовательской мутации.

### Recovery и миграция

- legacy `boards.json`, `documents/index.json`, `documents/*.json` и `folders.json` остаются читаемыми;
- прежний recovery-path `migration-v1` сохранён;
- неизвестная будущая `schemaVersion` блокируется до записи;
- corruption в journal или невозможность восстановить целую generation переводит repository в read-protected/error state вместо перезаписи исходников.

### Undo/Redo

`EditorViewModel` использует `DocumentHistory` вместо 50 полных snapshot всего `InkBoard`:

- обычные stroke/erase/move/OCR изменения хранят content delta;
- структура страниц хранится полностью только для редких add/delete/reorder операций;
- лимит: 100 операций и ориентировочно 32 MiB;
- cancel незавершённого input удаляет pending history operation;
- redo очищается новой подтверждённой редакторской операцией.

### Save acknowledgement

UI request sequence и локальный storage sequence разделены. Поздний ack старого request больше не может выставить `saving=false`, если в очереди уже находится более новая правка.

## 3. Автоматическая проверка

PR workflow теперь запускает:

`testDebugUnitTest assembleDebugAndroidTest lintRelease assembleRelease`

`assembleDebugAndroidTest` пока **компилирует**, но не исполняет instrumentation tests на устройстве. Поэтому факт зелёного CI нельзя трактовать как прохождение process-kill теста на реальном Android.

Добавлены проверки:

- operation history: stroke delta, erase delta, structural page operation, redo invalidation, memory/count trimming;
- transactional storage instrumentation: базовый round-trip, replay journal до checkpoint, оборванный trailing record, восстановление newest state через previous checkpoint + journal, блокировка повреждения в середине journal, unknown schema.

## 4. Измерения на реальном устройстве

Контрольное устройство по `docs/ERGONOMICS.md`: Galaxy Tab S10 FE Plus + штатный S Pen. Для каждого прогона записать Android, частоту дисплея, ориентацию, размер окна, font scale, питание и температуру.

| Метрика | Baseline | Метод |
|---|---|---|
| E05, тёплое открытие N1 p95 | не измерено | 20 открытий N1 |
| E06, input → frame p95 | не измерено | trace ≥100 штрихов; pen-to-photon отдельно видео |
| E07, dropped frames | не измерено | 5 минут N1 с письмом/scroll/save |
| E09, durable save p95 | не измерено | pen-up → ack после journal `fsync` |
| E13, RAM/background overhead | не измерено | N2/P2 и одинаковые baseline/sync прогоны |
| E15, 90 минут | не измерено | реальная лекционная сессия |

До аппаратного прогона эти поля не считаются `pass` или `fail`.

## 5. Критерии завершения программной части DATA-01

- confirmed journal mutation переживает создание нового `BoardRepository` без checkpoint;
- оборванный неподтверждённый хвост не уничтожает предыдущие confirmed entries;
- corruption в середине журнала не пропускается;
- newest checkpoint можно потерять/повредить без потери подтверждённого состояния, если предыдущий checkpoint и journal целы;
- document order и folder metadata восстанавливаются согласованно;
- migration не удаляет исходные файлы до публикации нового checkpoint;
- unknown schema не перезаписывается;
- старый save ack не скрывает более новую pending правку;
- Undo/Redo обычного письма не хранит полный документ на каждое действие.

## 6. Что остаётся аппаратной приёмкой

В этой среде нет физического S Pen и нет эмулятора/подключённого Android-устройства в GitHub Actions. Поэтому E06, реальный process kill в момент storage fault, ENOSPC на файловой системе устройства, palm rejection и 90-минутная стабильность должны быть подтверждены отдельным validation-коммитом после аппаратного прогона. До этого программная foundation может быть слита, но `BASE-01` не считается полностью закрытым по аппаратным метрикам.
