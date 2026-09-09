# Recovered worktree checkpoint — 2026-09-09

This commit is a recovery checkpoint for the unfinished implementation session that ended before the final local recheck/commit.

## Recovered implementation

### Document/content path

- one-based page-range parser (`1-8,12`) with validation/deduplication;
- range-aware PDF import built on the immutable `AssetStore`;
- byte-budgeted LRU render cache with generation checks so stale asynchronous results are not reused;
- lazy PDF/image background decoder with bounded bitmap dimensions/RAM and closed `PdfRenderer`/`ParcelFileDescriptor` lifetimes;
- PDF/PNG exporter that renders imported source backgrounds before ink/text/LaTeX;
- `.inklab` package v3 with versioned manifest, hashes, staged ZIP validation, referenced PDF/image assets and completed audio segments;
- explicit `RESTORE` (same logical IDs) and `COPY` (consistent remap) identity modes;
- legacy backup v2 remains in `DocumentTransfer` and is not silently reinterpreted as v3.

### Cloud preview foundation

- account-scoped durable outbox with stable operation IDs, restart recovery and bounded retry;
- Drive v3 REST transport with app-created root, pagination, download, resumable chunk upload and uncertain-result reconciliation;
- immutable revision graph, causal head comparison and explicit divergence/conflict state independent of timestamps;
- atomic local cloud catalog for revisions, folder metadata events and tombstones; duplicate immutable IDs with changed payload are rejected;
- persisted selection / Wi-Fi-only / include-audio / pause preferences and a cloud-settings dialog component;
- safe diagnostics with token/session URL redaction;
- infrastructure/limitations documented in `integrations/GOOGLE_DRIVE.md`.

### Contract coverage added

Pure JVM coverage was added for:

- page range parsing including `1-8,12`;
- LRU byte eviction and stale generation rejection;
- causal ordering/divergence, immutable duplicate rejection and account-scoped stable operation IDs.

The pure Kotlin production subset (`PageRange`, `RenderCache`, `CloudModels`) was syntax-compiled during recovery with local `kotlinc`.

## Verification status

The full Android Gradle gate is **not marked passed** in this checkpoint.

Blocker carried from the interrupted session: Gradle wrapper startup/dependency resolution could not reach `services.gradle.org`. The previous local command executor then disconnected before the final repeat of unit/instrumentation checks and before commit. Therefore this recovery commit must be treated as a continuation point, not a release-quality verification result.

Recommended first local commands after checkout:

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew assembleDebug --no-daemon
```

Then run the existing Android instrumentation suite, including `DocumentImportTest`, on emulator/device.

## Acceptance items intentionally still open

- wire cloud authorization/account UI to a production OAuth gateway;
- wire the cloud-settings dialog into final navigation/auth flow;
- wire outbox execution to constrained WorkManager/foreground reconciliation;
- apply remote heads/conflict resolution into the live editor with local-sequence recheck;
- wire the lazy source-background renderer into the live editor and compare editor vs exported PDF/PNG;
- fault-injection for package install, interrupted upload/download and ENOSPC;
- two-device offline conflict/tombstone/folder-cycle scenarios;
- remote history cleanup policy and production Drive changes-cursor strategy.
