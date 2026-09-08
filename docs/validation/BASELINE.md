# Baseline локального хранения и истории

Дата фиксации: 8 сентября 2026.
Исходный commit для сравнения: `c9db8fb50771c724ae86524a572f70a09099a85e`.
Проверенный P1 code head: `6733b9ecb85c22b384f36c48db0c665c7c27fd82`.
Ветка реализации: `storage-foundation`, PR #5.

Этот файл фиксирует исходную точку и результат этапа `BASE-01 / DATA-01`. Программная часть P1 считается завершённой в пределах того, что можно проверить без физического планшета. Метрики S Pen, реального накопителя и длительной лекционной сессии намеренно не выдумываются и остаются отдельной аппаратной приёмкой.

## 1. Исходное состояние на c9db8fb

| Область | Состояние | Риск |
|---|---|---|
| Документы | отдельные `documents/<id>.json` через `AtomicFile` | отдельный документ атомарен, библиотека целиком — нет |
| Индекс | `documents/index.json` публикуется отдельно | process death между payload и index может оставить несогласованное состояние |
| Папки | `folders.json` сохраняется отдельным вызовом | документ и структура папок не имеют общей commit boundary |
| Autosave | `Channel.CONFLATED` со снимком документов и папок | нет durable revision |
| Статус save | любой завершившийся save мог снять `saving` | старый ack мог скрыть новую ожидающую правку |
| Undo/Redo | `ArrayDeque<DocumentSnapshot>` с полным `InkBoard` | стоимость истории росла с размером всего документа |
| Лимит history | 50 snapshots | лимит по количеству, а не по памяти |
| Схема | writer пишет `schemaVersion=2` | baseline-reader не запрещал будущую неизвестную схему |
| CI | JVM tests + lint + release build | storage instrumentation не исполнялся |

## 2. Итог P1: durable local store

### Journal и локальная последовательность

Каждая подтверждённая логическая мутация получает монотонный `journalSequence`. Запись считается durable только после `FileDescriptor.sync()`.

Журнал — NDJSON с SHA-256 каждой записи. Поддерживаются:

- восстановление confirmed mutations после restart до следующего checkpoint;
- один оборванный trailing record как неподтверждённый хвост;
- атомарное удаление такого хвоста перед следующим append;
- блокировка corruption не в хвосте;
- проверка непрерывности sequence, чтобы удалённую confirmed запись нельзя было молча пропустить;
- обратная совместимость replay с journal mutation v1.

### Page-level hot path

Journal mutation v2 больше не сериализует целый большой документ после обычного pen-up.

Для существующего документа с неизменной структурой страниц записываются:

- durable metadata документа;
- только реально изменившиеся page payloads.

Изменение только названия/метаданных не включает page payload. Full-document upsert используется для нового документа и структурных изменений page list, где это проще и безопаснее для recovery.

Contract-test на 100-страничной тетради требует, чтобы mutation одного изменённого листа была меньше `1/8` полного JSON документа и точно восстанавливалась после нового `BoardRepository`.

### Checkpoint

Checkpoint — immutable generation с manifest и A/B payload slots. `CURRENT` переключается только после записи и проверки manifest/payload. Физический `generationId` отделён от `journalSequence`.

После checkpoint сохраняется участок journal, необходимый для восстановления через предыдущую валидную generation. Loader умеет:

- восстановить повреждённый `CURRENT`;
- отбросить повреждённый newest manifest/payload;
- вернуться к предыдущей целой generation;
- replay confirmed journal до newest logical state;
- после следующего успешного checkpoint удалить abandoned/corrupt generation, не удаляя последнюю реально валидную.

### Recovery и миграция

- legacy `boards.json`, `documents/index.json`, `documents/*.json` и `folders.json` остаются читаемыми;
- до migration сохраняется `migration-v1`;
- повторный запуск migration не дублирует документы и не публикует лишнюю generation;
- unknown future `schemaVersion` блокируется до записи;
- при explicit recovery повреждённый store сначала целиком копируется в `recovery-*`, затем изолируется;
- failed append не продвигает `lastCommittedSequence`, после устранения ошибки тот же repository может безопасно retry.

## 3. Viewport отделён от durable document state

`savedScale`, `savedOffsetX`, `savedOffsetY` остаются временно в конструкторе `InkBoard` только для совместимости старого UI/legacy decode, но:

- новый durable JSON их не записывает;
- document equality/hash их не учитывает;
- viewport-only изменение не увеличивает local sequence;
- viewport-only изменение не создаёт journal record.

Это позволяет дальше вынести сам session viewport в отдельный UI-state без миграции формата документа и исключает pan/zoom из autosave/sync semantics.

## 4. Operation-based Undo/Redo

`EditorViewModel` использует `DocumentHistory` вместо 50 полных snapshots всего `InkBoard`:

- strokes/erase/move/OCR/converted objects сохраняются content delta;
- add/delete/reorder страниц — structural operation;
- один пользовательский жест формирует одну history operation;
- лимит: 100 операций / ориентировочно 32 MiB;
- при одной oversized newest operation она остаётся отменяемой, а старые операции вытесняются первыми;
- cancel pending input не оставляет ложную Undo-команду;
- OCR Undo возвращает исходные `sourceStrokes`;
- page reorder round-trip проверяется отдельно.

Durable journal и пользовательская Undo-history независимы и не заменяют друг друга.

## 5. Save acknowledgement

UI request sequence и storage sequence разделены. Поздний ack старого request не может показать `Сохранено`, если уже существует более новая pending правка.

Durable acknowledgement относится только к успешно записанной/`fsync` journal mutation или опубликованному checkpoint.

## 6. Автоматическая приёмка P1

GitHub Actions для PR выполняет два независимых job.

### Build

Успешно на code head `6733b9e`:

- `testDebugUnitTest`;
- `assembleDebugAndroidTest`;
- `lintRelease`;
- `assembleRelease`;
- проверка release signature.

### Android storage instrumentation

Успешно на:

- Android API 35;
- `aosp_atd`;
- x86_64 emulator;
- `connectedDebugAndroidTest`.

Фактический результат финального P1 run: **21/21 tests passed**.

Suite включает `TransactionalStorageTest` и `P1StorageContractTest` и покрывает:

- transactional round-trip + document order + folders;
- legacy migration и repeat migration;
- journal replay до checkpoint;
- failed append + retry;
- truncated tail + subsequent repair;
- broken `CURRENT`;
- corrupt newest manifest;
- corrupt document payload;
- fallback previous checkpoint + journal;
- повреждение одного document payload при наличии других confirmed документов;
- corruption внутри journal;
- explicit recovery;
- future schema rejection;
- viewport-only no-op durability;
- отсутствие viewport в durable encoding;
- page-level mutation на 100-page document;
- structural full-upsert fallback;
- metadata-only mutation без page serialization;
- journal v1 replay compatibility.

Поэтому **DATA-01 программно принят без аппаратной части**.

## 7. Что остаётся аппаратной приёмкой и не блокирует переход к P2

Контрольное устройство: Galaxy Tab S10 FE Plus + штатный S Pen.

| Метрика | Статус | Метод |
|---|---|---|
| E05, тёплое открытие N1 p95 | не измерено | 20 открытий N1 |
| E06, input → frame / pen-to-photon | не измерено | trace + high-speed video |
| E07, dropped frames | не измерено | 5 минут N1 с письмом/scroll/save |
| E09, durable save p95 | не измерено | pen-up → ack после journal fsync |
| E13, RAM/background overhead | не измерено | N2/P2 на реальном memory class |
| E15, 90 минут | не измерено | реальная лекционная сессия |
| literal ENOSPC | не проверено физически | заполнение test storage / fault run |
| process kill вокруг fsync | не проверено физически | kill-process fault matrix |
| S Pen/palm rejection | не проверено физически | реальные touch/stylus последовательности |

CI покрывает программные аналоги write failure, truncated write, restart, replay и corruption, но они не подменяют характеристики физического накопителя и S Pen.

## 8. Статус этапа

- `BASE-01`: программный baseline и методы сравнения — **закрыты**; аппаратные значения отложены в hardware validation.
- `DATA-01`: **закрыт программно**.
- operation-based history foundation: **закрыта программно**.
- P1 без аппаратной проверки: **готов к merge и переходу к P2**.
