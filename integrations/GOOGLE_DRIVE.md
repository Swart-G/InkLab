# Google Drive preview: infrastructure and operational contract

Status: preview foundation. Local InkLab storage is authoritative; Drive failure must never block writing or local commit.

## Scope

The preview stores immutable `.inklab` revisions and immutable metadata events in an app-created Drive folder. It does not mirror the local filesystem and does not use Drive as the live document database.

Implemented foundation:

- `DriveRestClient`: Drive v3 REST transport with paginated listing, app-created root discovery/creation, download, resumable chunk upload and uncertain-result reconciliation by stable `operationId` in `appProperties`;
- `DurableCloudOutbox`: account-scoped durable operations, restart recovery, bounded exponential retry and idempotent enqueue;
- `CloudCatalogStore`: atomic local catalog of immutable revisions, tombstones and folder events;
- `CloudCausality`: causal-head comparison independent of device wall clock; divergent offline branches remain conflicts;
- `CloudPreferenceStore`: document selection, Wi-Fi only, audio inclusion, pause, account/root identity;
- `CloudDiagnostics`: safe status export that omits/redacts access tokens and resumable session URLs.

## OAuth setup

The authorization UI/gateway must provide a short-lived access token to `DriveAccessTokenProvider`. Tokens are not persisted by the transport, are not written to `.inklab`, and must not be included in diagnostics.

For Android OAuth configuration use the application id `dev.swart.inklab` and the signing certificate that will actually ship. Do not commit client secrets or refresh tokens. The preview assumes reauthorization can be required after process/account lifecycle changes until the authorization gateway is completed.

## Remote layout

The root folder is created by InkLab and identified by `appProperties.inklabRoot=v1`; its Drive file ID is the stable identity. A rename does not change this identity.

Recommended children:

- immutable package revisions (`*.inklab`), each with `operationId`, document ID, revision ID and snapshot sequence in `appProperties`;
- immutable folder events;
- immutable tombstones.

Do not make a remote deletion equivalent to a local wipe. Missing root/access revocation is an error/recovery state; local documents remain available.

## Upload protocol

1. Local durable save completes and yields a snapshot sequence.
2. A complete `.inklab` snapshot is created in local staging.
3. A stable account-scoped `operationId` is enqueued.
4. Executor checks Drive for the same `operationId` before starting a second logical upload.
5. Resumable upload publishes immutable bytes in chunks.
6. On an uncertain final response, query by `operationId`; do not blindly create another revision.
7. Only after remote verification mark the outbox operation complete and add the immutable revision to the local cloud catalog.
8. A newer local edit remains a new pending sequence; an old acknowledgement never marks it synced.

Retention in preview: no automatic deletion of published revisions.

## Download / restore

Listing is paginated. Download goes to `.part`/staging first; package paths, hashes, schema and references are validated before local publication. `InkLabPackage` has two identity modes:

- `RESTORE`: preserve document/page/folder/audio IDs for same-library recovery;
- `COPY`: consistently remap logical IDs and suffix copied document titles.

A corrupted or newer package must not replace a live local document.

## Conflict contract

Revision order is causal, not timestamp-based. `CloudCausality` returns `DIVERGED` when neither head is an ancestor of the other. Clock skew therefore cannot silently select a winner. Different-page edits still conflict in this preview; conflict UI/resolution may later create a revision with both selected parent heads.

A duplicate immutable event/revision ID with a different payload is rejected as corruption instead of being silently accepted.

## Folder metadata and tombstones

Folder changes and deletions are immutable events in `CloudCatalogStore`; they are not mutable Drive-folder state. Tombstones are also immutable events and are published/recorded atomically in the local catalog. Orphans/cycles must be resolved at the metadata-application layer without hiding live documents.

## Scheduler contract

WorkManager/foreground execution must only execute `DurableCloudOutbox`; it must not be the source of truth. On restart, `RUNNING` operations return to retry state. Retry is bounded. Unlink marks old-account pending work failed and must never transfer it into a new account.

Wi-Fi-only and pause are persisted settings. The preview intentionally does not implement a tight background polling loop.

## Known preview limits (2026-09-09)

- app-created Drive root only; arbitrary existing-folder grant/picker flow is not declared production-ready;
- initial reconciliation may require a full paginated rescan rather than a long-lived Drive changes cursor;
- applying a remote head to an open local document still requires explicit higher-level coordination/UI;
- cloud preference dialog exists as a UI component but still needs final navigation/auth wiring;
- no automatic remote history garbage collection;
- authorization lifecycle can require reauthorization after process/account state changes;
- no production claim until real-device OAuth, offline/reboot, 401/403/429/5xx and two-device conflict scenarios are run.
