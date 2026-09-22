# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A Telegram bot (`ai.lab.weeklyreport`) that:

1. Listens to a source Telegram channel; on every `monthly_report.xlsx` posted there, parses it and
   upserts the data into PostgreSQL (idempotently, with an audit trail of processed files).
2. Every Monday at 9:00 Asia/Almaty, assembles last week's report from the DB (marketplaces, loyalty
   program, summary) and sends the resulting `.xlsx` to a reports chat.
3. Watches that new files keep arriving from the source channel; if none arrives for longer than
   `ingestion-watchdog.max-silence` (default 26h), sends an alert to the reports chat.

Each `monthly_report.xlsx` contains the entire accumulated history for the month (not a delta), so
upserts are safe to replay and missed days self-heal on the next successfully processed file. The
only real data-loss case is the bot being down longer than Telegram retains unconfirmed updates
(~24h) — the Bot API has no way to fetch channel history retroactively; recovery then requires
manually re-posting the file to the channel.

## Commands

```bash
./gradlew clean build          # full build: compiles + runs unit tests + integration test
./gradlew test                 # unit + integration tests only
./gradlew test --tests "ai.lab.weeklyreport.excel.MonthlyReportParserTest"   # single test class
./gradlew test --tests "*.WeeklyReportGeneratorTest.someMethodName"          # single test method
./gradlew bootRun              # run locally (needs env vars below + DB reachable)
./gradlew generateTestFixtures # regenerate src/test/resources/fixtures/monthly_report_test.xlsx
                                # via TestFixtureGenerator — run after changing its structure
./gradlew dependencyUpdates    # check for newer dependency versions
```

On Windows use `gradlew.bat` instead of `./gradlew`.

`DailyMetricRepositoryIntegrationTest` uses Testcontainers against real PostgreSQL — Docker must be
running for `build`/`test` to pass.

### Local run (DB in Docker, app from IDE/gradlew)

`.env` is only read automatically by `docker compose`; `bootRun`/IDE runs need env vars set manually:

```bash
docker compose up -d postgres   # publishes on POSTGRES_PORT (default 6868)
```

Then set `TELEGRAM_BOT_TOKEN`, `TELEGRAM_SOURCE_CHANNEL_ID`, `TELEGRAM_REPORT_CHAT_ID` in the run
config (or use an EnvFile plugin pointing at `.env`) — the bot won't register with Telegram without
them. `SPRING_DATASOURCE_*` defaults in `application.yml` already match the docker-compose Postgres
(`localhost:6868`/`weeklyreport`/`weeklyreport`), so they don't need to be set for local dev.

Full stack: `docker compose up --build` (Postgres has a healthcheck; app starts after and runs Flyway
migrations on startup).

## Architecture

```
config/     @ConfigurationProperties records (telegram.bot, weekly-report incl. divisions/daribar-crosswalk paths, ingestion-watchdog) + Clock/TelegramClient beans
metric/     metric catalog (numeric ids 1-18) and domain DTOs, incl. division/pharmacy-directory DTOs
excel/      monthly_report.xlsx / divisions.xlsx / daribar_crosswalk.xlsx parsing + weekly report .xlsx generation
repository/ JdbcTemplate: batch upsert daily_metrics, pharmacy_directory/daribar_crosswalk reference data, division report aggregates, ingested_files audit
service/    orchestration: file ingestion, startup reference-data loading, weekly report assembly/send
telegram/   bot (receives files) + sender (messages/documents)
scheduler/  weekly cron trigger + silent-channel watchdog
```

**Data flow — ingestion**: `ReportTelegramBot` (long-polling `channel_post` updates) → filters to
`.xlsx` documents from the configured source channel → downloads via `TelegramClient` into a temp
file (deleted in a `finally`, so nothing accumulates on disk) → `IngestionService.ingest()`: checks
`ingested_files.telegram_file_id` for idempotency, then `MonthlyReportParser.parse(path, consumer)`
streams rows into `IngestionService.ChunkedWriter`, which flushes them to
`DailyMetricRepository.upsertAll()` every 10k rows (batched `ON CONFLICT DO UPDATE` on
`(pharmacy_code, metric_num, metric_date)`) → records an `ingested_files` audit row. All in one
transaction: either the whole file landed plus its audit row, or nothing.

**Ingestion is memory-bound, and that is why it's streamed end to end.** A month's file holds ~1.7M
values; the original `new XSSFWorkbook(InputStream)` + `List<MetricRow>` approach peaked near a
gigabyte and the kernel OOM-killer kept SIGKILLing the container mid-parse (no stacktrace in the
logs, just a fresh Spring banner, and the day's file lost for good — Telegram confirms the update
offset on receipt, not after processing). So: parse via SAX (`XSSFReader`, not usermodel), open from
a `Path` (`OPCPackage.open(File)` reads zip entries lazily; from an `InputStream` POI buffers the
whole archive), and never hold more than one chunk of rows. Measured live heap mid-parse on a
1.65M-value file: ~16MB. Don't reintroduce a `List`-returning parse overload — that is the exact
shape that caused the outage. `app` also gets `mem_limit` + `-XX:MaxRAMPercentage` in
`docker-compose.yml` so any future overrun surfaces as a logged `OutOfMemoryError` instead of a
silent kill; `DivisionsFileParser`/`DaribarCrosswalkParser` stay on usermodel (their files are tiny).

**Data flow — reporting**: `WeeklyReportScheduler` (`@Scheduled` cron from `weekly-report.cron`/
`.timezone`) → `WeeklyReportService.generateAndSendWeeklyReport()`: computes current/previous
`WeekRange` (Mon-Sun) via `WeekRange.containingWeekBefore(today)`, pulls aggregated
`MetricDailyTotal`s from `DailyMetricRepository.findDailyTotals()` plus division-level aggregates
from `DivisionReportRepository`/`PharmacyDirectoryRepository`, builds the workbook via
`WeeklyReportGenerator.generate()` (sheets in this order: "1. Маркетплейсы", "2. Программа
лояльности", "3. Дивизионы" - built on `DivisionMetricPivot` instead of the day-keyed `MetricPivot`,
current week only, no prior-week comparison - and "4. Сводный"; every sheet gets a navy title/period
band via `WeeklyReportGenerator.writeTitleBand()`, and `ReportStyles` centralizes the whole
dark-navy/accent-blue design system as POI cell styles - this design mirrors a hand-built reference
file the business supplied, not an arbitrary choice, so changes here should stay visually consistent
with it), sends via `TelegramSender.sendDocument()`. Failures are logged with full stacktrace and a
short error message is also sent to the reports chat (`WeeklyReportScheduler.notifyFailure`), so
failures are visible without digging through logs.

**Ingestion watchdog**: `IngestionWatchdog` runs on a fixed rate (`ingestion-watchdog.check-interval`),
compares `now` against `IngestedFileRepository.findLastIngestedAt()`. Alerts once per "silence
episode" via an `AtomicBoolean` latch that resets as soon as a fresh file arrives again — avoids
spamming the same alert on every check.

**Metric catalog** (`MetricCatalog`, `metric/` package): the source spreadsheet encodes each metric
row as a numeric id embedded in a label like `"11-Заказы Daribar (кол-во)"`. Ids 1-5 are the core
loyalty program, 6-10 are the same 5 measures for the "Janymda" loyalty program, 11-18 are
marketplace order count/sum pairs for four channels (Daribar/Glovo/Emdel/Wolt — see
`MarketplaceChannel`). `MetricCatalog` centralizes the id-range arithmetic (`isLoyalty`,
`isMarketplace`, `loyaltyProgram`, `loyaltyMeasure`, `marketplaceKind`, `parseMetricNum`) — this is
the one place that knows how ids map to domain concepts; both the parser and the report generator go
through it rather than hardcoding ranges.

**monthly_report.xlsx parsing** (`MonthlyReportParser`): one sheet per month (Russian sheet names,
e.g. "Июль"); the year isn't stored in the file, so it's inferred from the current date — a month
later than the current one is assumed to be the previous year (handles a "Декабрь" sheet encountered
in January). Header row is located dynamically by scanning for a "Код аптеки" cell (within the first
10 rows), and day columns are whatever numeric-labeled columns follow — `"Итого"`/`"Есть данные"`
columns are recognized and skipped. Row processing stops at the first row with an empty pharmacy
code, or at a gap in the sheet's row numbering (`<row r="…">` jumping ahead means the row isn't in
the file at all — this is the streaming equivalent of the old `sheet.getRow(i) == null` check, and
both are covered by `MonthlyReportParserTest`).

**Divisions sheet and branch_code resolution**: two reference spreadsheets, loaded once at
application startup (not on the weekly schedule) by `ReferenceDataLoader` (`ApplicationRunner`),
each independently: a missing/unset path or missing file just logs a WARN, the app still starts and
that reference data stays empty. Paths come from `weekly-report.divisions-path`/
`.daribar-crosswalk-path` (env `DIVISIONS_FILE_PATH`/`DARIBAR_CROSSWALK_PATH`). Every load is a full
replace (`TRUNCATE` + batch insert in one transaction), not an incremental merge.

- `divisions.xlsx` → `DivisionsFileParser` → `pharmacy_directory` table (`branch_code` PK, address,
  city, division_num, director_name) via `PharmacyDirectoryRepository.reloadAll()`. This is the
  aptека→division→director mapping (`V2__pharmacy_divisions_stub.sql`'s stub table is superseded and
  dropped by `V3__division_reference_data.sql`).
- `daribar_crosswalk.xlsx` → `DaribarCrosswalkParser` → `daribar_crosswalk` table (`daribar_code` PK,
  branch_code, pharmacy_name, comment) via `DaribarCrosswalkRepository.reloadAll()`. Known source
  data quirk: duplicate `daribar_code` rows can occur - tolerated, last row in the file wins (JDBC
  batch executes one upsert statement per row in file order).
- **branch_code resolution** (`DailyMetricRepository.UPSERT_SQL`): for each ingested row, priority is
  1) the row's own `Код филиала` if non-blank, 2) else a live subquery against `daribar_crosswalk`
  matching `pharmacy_code` = `daribar_code`, 3) else `NULL`. This happens directly in the upsert SQL
  (`COALESCE(NULLIF(:branchCode, ''), (SELECT branch_code FROM daribar_crosswalk WHERE daribar_code = :pharmacyCode))`),
  re-evaluated via `EXCLUDED.branch_code` on every conflict - so existing rows self-heal as
  `daribar_crosswalk` changes, on the next re-ingest of the same (always-full-history)
  `monthly_report.xlsx`. `daily_metrics.branch_code` is nullable as of `V3`.
- **"Дивизионы" sheet** (`WeeklyReportGenerator.buildDivisionsSheet`): one row per division from
  `PharmacyDirectoryRepository.findDivisionRegistry()` (static registry counts/coverage), joined
  against current-week aggregates from `DivisionReportRepository` (`LEFT JOIN daily_metrics ...
  pharmacy_directory` - a `NULL` division bucket naturally captures both unresolved and
  no-directory-match branch_codes), then "ИТОГО ПО СЕТИ" below the division rows, whose activity %
  is a ratio of summed counts, not an average of per-division percentages. Pharmacies with no
  resolved division (`NULL` bucket) are deliberately **excluded** from this sheet entirely - no row,
  no contribution to "ИТОГО ПО СЕТИ" - since their orders aren't attributable to any real
  division/director; that data still surfaces at the network level via the "1. Маркетплейсы" sheet
  (which isn't scoped by division at all). `division_num` values of "Закрыта"/"закрыта"
  (case-insensitive) are a source-data status marker, not a real division number - both
  `PharmacyDirectoryRepository.findDivisionRegistry()` and the two `DivisionReportRepository`
  aggregate queries normalize them to `NULL` (same `CASE WHEN LOWER(TRIM(...))` expression in all
  three), so a "closed" pharmacy folds into the same excluded bucket rather than appearing as a
  phantom division row.
- **"Сводный" sheet's "ПРОБЛЕМЫ" section** (`WeeklyReportGenerator.writePlaceholderRow`): two rows
  ("Отмены заказов МП", "Дивизионов с невыполнением плана") that always render as "н/д" - there is no
  cancellation or plan/target data anywhere in the schema or source files, so these are the reference
  design's section kept for visual parity, not live metrics. Don't backfill them with fabricated
  numbers; wire up real data only if a genuine source for cancellations/plans shows up.

## Stack notes

Java 21, Spring Boot 4.1.0, Gradle Kotlin DSL (wrapper 9.6.1), PostgreSQL 16 + Flyway, Apache POI
(`poi-ooxml`, version pinned explicitly — not managed by the Spring Boot BOM), TelegramBots
(`telegrambots-springboot-longpolling-starter` + `telegrambots-client`, also pinned explicitly),
Testcontainers. PostgreSQL driver, Flyway, and Testcontainers versions are intentionally *not* pinned
in `build.gradle.kts` — they're inherited from the `spring-boot-dependencies:4.1.0` BOM to guarantee
mutual compatibility. See the "Версии зависимостей" table in README.md for the full rationale and
sources checked for each version.

Virtual threads are enabled (`spring.threads.virtual.enabled: true`).

Cron schedule is never hardcoded — always driven by `weekly-report.cron`/`weekly-report.timezone`
(env vars `WEEKLY_REPORT_CRON`/`WEEKLY_REPORT_TIMEZONE`), so changing the schedule only needs a
restart, not a rebuild.
