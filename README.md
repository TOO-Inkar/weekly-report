# weekly-report-bot

Telegram-бот, который:

1. слушает канал-источник и на каждый выложенный `monthly_report.xlsx` парсит его и складывает
   данные в PostgreSQL (идемпотентно, с аудитом обработанных файлов);
2. каждый понедельник в 9:00 по Алматы собирает из БД отчёт за прошлую неделю (маркетплейсы,
   программа лояльности, сводка, дивизионы) и отправляет готовый `.xlsx` в чат с отчётами;
3. следит, что новые файлы из канала-источника вообще приходят - если файла нет дольше
   `ingestion-watchdog.max-silence` (по умолчанию 26 часов), шлёт алерт в чат с отчётами.

Файл каждый раз содержит всю накопленную историю (не дельту), поэтому даже если бот пропустил
несколько дней (был выключен, канал молчал), следующий же успешно обработанный файл сам
"довозит" все пропущенные даты - специально ничего пересылать не нужно. Единственный случай,
когда данные реально теряются - если бот не работал дольше, чем Telegram хранит неподтверждённые
апдейты (порядка суток): у Bot API нет способа запросить историю канала задним числом, поможет
только повторная отправка файла в канал вручную.

## Стек

- Java 21, Spring Boot 4.1.0, Gradle (Kotlin DSL), Gradle Wrapper 9.6.1
- PostgreSQL 16 + Flyway
- Apache POI (парсинг и генерация `.xlsx`)
- [TelegramBots](https://github.com/rubenlagus/TelegramBots) (`telegrambots-springboot-longpolling-starter`)
- Testcontainers (интеграционные тесты с реальным PostgreSQL)

Выбор конкретных версий и источники, откуда они взяты, - в разделе
["Версии зависимостей"](#версии-зависимостей) ниже.

## Структура проекта

```
src/main/java/ai/lab/weeklyreport/
├── config/      - @ConfigurationProperties, бины TelegramClient/Clock
├── metric/      - справочник метрик (id 1-18) и доменные DTO (в т.ч. справочник дивизионов)
├── excel/       - парсинг monthly_report.xlsx/divisions.xlsx/daribar_crosswalk.xlsx и генерация недельного отчёта
├── repository/  - JdbcTemplate: batch upsert daily_metrics, справочники дивизионов/Daribar, аудит ingested_files
├── service/     - оркестрация приёма файла, загрузка справочников при старте, сборка/отправка отчёта
├── telegram/    - бот (приём файлов) и отправка сообщений/документов
└── scheduler/   - еженедельный запуск по cron

src/main/resources/db/migration/  - Flyway-миграции
```

## Быстрый старт (Docker)

1. Скопируйте `.env.example` в `.env` и заполните значения (см. ниже, как получить
   `TELEGRAM_BOT_TOKEN` и id чатов).
2. Запустите:

   ```bash
   docker compose up --build
   ```

   Postgres поднимется первым (есть healthcheck), приложение стартует после него и само
   применит Flyway-миграции при старте.

## Как получить токен бота и chat_id канала

1. Создайте бота через [@BotFather](https://t.me/BotFather) командой `/newbot`, получите
   `TELEGRAM_BOT_TOKEN`.
2. Добавьте бота **администратором** в канал-источник (тот, куда публикуется
   `monthly_report.xlsx`) и в чат/канал, куда должен уходить готовый недельный отчёт.
   Боту нужны права на чтение сообщений канала и на отправку сообщений/файлов в чат отчётов.
3. Чтобы узнать числовой `chat_id` канала:
   - перешлите (forward) любое сообщение из канала боту
     [@userinfobot](https://t.me/userinfobot) или [@RawDataBot](https://t.me/RawDataBot) -
     в пересланном сообщении будет поле `forward_from_chat.id` (обычно отрицательное число
     вида `-100xxxxxxxxxx` для каналов);
   - либо временно сделайте у бота вызов `getUpdates` (например, через
     `https://api.telegram.org/bot<TOKEN>/getUpdates`) после того, как в канале появится
     новый пост при добавленном боте, и посмотрите `channel_post.chat.id` в ответе.
   - Для публичного канала с `@username` можно вместо числового id указать
     `TELEGRAM_SOURCE_CHANNEL_ID=@username_канала` - бот поддерживает оба варианта.
4. Заполните `TELEGRAM_SOURCE_CHANNEL_ID` и `TELEGRAM_REPORT_CHAT_ID` в `.env`.

## Конфигурация (переменные окружения)

| Переменная                    | Назначение                                             | По умолчанию              |
|--------------------------------|--------------------------------------------------------|----------------------------|
| `TELEGRAM_BOT_TOKEN`           | Токен бота от BotFather                                | -                          |
| `TELEGRAM_SOURCE_CHANNEL_ID`   | Канал-источник `monthly_report.xlsx`                   | -                          |
| `TELEGRAM_REPORT_CHAT_ID`      | Куда слать готовый недельный отчёт                     | -                          |
| `SPRING_DATASOURCE_URL`        | JDBC URL PostgreSQL                                    | `jdbc:postgresql://localhost:6868/weeklyreport?reWriteBatchedInserts=true` |
| `SPRING_DATASOURCE_USERNAME`   | Пользователь БД                                        | `weeklyreport`             |
| `SPRING_DATASOURCE_PASSWORD`   | Пароль БД                                              | `weeklyreport`             |
| `POSTGRES_PORT`                | Порт на хосте для Postgres из docker-compose (только для docker-compose, см. ниже) | `6868` |
| `WEEKLY_REPORT_CRON`           | Cron-расписание генерации отчёта                       | `0 0 9 * * MON`            |
| `WEEKLY_REPORT_TIMEZONE`       | Таймзона для cron                                      | `Asia/Almaty`              |
| `WEEKLY_REPORT_EMAIL_RECIPIENTS` | Кому слать сам файл отчёта на почту (через запятую); пусто - только Telegram | пусто |
| `WEEKLY_REPORT_NOTIFY_RECIPIENTS` | Кому слать короткое статус-письмо без вложения после каждого цикла доставки (успех/финальный сбой); задано пустым - выключено | `it@inkar.kz,m.muratbekuly@inkar.kz,t.kessikov@inkar.kz` |
| `WEEKLY_REPORT_DELIVERY_MAX_ATTEMPTS` | Сколько всего попыток доставки отчёта при временных сбоях | `7` |
| `WEEKLY_REPORT_DELIVERY_INITIAL_BACKOFF` | Пауза перед 2-й попыткой (дальше удваивается)        | `2m`                       |
| `WEEKLY_REPORT_DELIVERY_MAX_BACKOFF` | Потолок паузы между попытками                          | `30m`                      |
| `WEEKLY_REPORT_CATCH_UP_ON_STARTUP` | Догонять пропущенный плановый запуск при старте       | `true`                     |
| `INGESTION_WATCHDOG_MAX_SILENCE`    | Через сколько молчания канала-источника слать алерт    | `26h`                      |
| `INGESTION_WATCHDOG_CHECK_INTERVAL` | Как часто проверять, не молчит ли канал                | `1h`                       |
| `DIVISIONS_FILE_PATH`          | Путь к `divisions.xlsx` (справочник аптека → дивизион → директор)  | - (обязателен, без значения по умолчанию) |
| `DARIBAR_CROSSWALK_PATH`       | Путь к `daribar_crosswalk.xlsx` (сопоставление кода аптеки в Daribar с branch_code) | - (обязателен, без значения по умолчанию) |

## Устойчивость доставки недельного отчёта

Плановый запуск (понедельник 9:00) не теряет отчёт из-за временного сбоя в этот момент
(например, после перезагрузки хоста ещё не готовы сеть/DNS):

- **Повторные попытки.** Если недоступна БД (отчёт не собрать), Telegram или SMTP, попытка
  повторяется с нарастающей паузой: по умолчанию 7 попыток, паузы 2, 4, 8, 16, 30, 30 мин
  (~1.5 часа). Каналы независимы: упал Telegram - email всё равно уходит, и наоборот.
- **Без дублей.** Каждая успешная отправка отмечается в таблице `weekly_report_deliveries`
  (неделя + канал); повторы и догоняющий запуск шлют отчёт только туда, куда он ещё не ушёл.
- **Догоняющий запуск при старте.** Spring не выполняет пропущенные срабатывания cron, поэтому
  при старте приложение берёт последнее плановое срабатывание за 7 дней и, если отчёт за его
  неделю доставлен не во все каналы, отправляет его (с теми же повторами). Пока журнал доставки
  пуст (первый запуск после появления таблицы), догоняющий запуск не делается - чтобы не
  продублировать отчёт, отправленный до деплоя.
- **Уведомление о финальном сбое.** Если все попытки исчерпаны, сообщение с причиной уходит и в
  Telegram-чат отчётов, и получателям `WEEKLY_REPORT_EMAIL_RECIPIENTS` - так о сбое Telegram
  узнают по почте, о сбое почты - в Telegram. Письмо о сбое уходит и получателям
  `WEEKLY_REPORT_NOTIFY_RECIPIENTS`, даже если `WEEKLY_REPORT_EMAIL_RECIPIENTS` пуст. Всё
  дополнительно логируется (ERROR).
- **Статус-письмо.** После каждого цикла, в котором отчёт реально отправлялся (плановый или
  догоняющий запуск), получателям `WEEKLY_REPORT_NOTIFY_RECIPIENTS` уходит короткое письмо без
  вложения: "успешно отправлен" (каналы, имя файла, время) или уведомление о финальном сбое. Если
  отправлять было нечего (всё уже доставлено) - письма нет. Ошибка отправки статус-письма только
  логируется и на доставку не влияет. SMTP-соединение проверяется при старте, если задан хотя бы
  один из двух списков получателей.

Healthcheck для контейнера `app` в `docker-compose.yml` не добавлен: в приложении нет HTTP-сервера
и Spring Boot Actuator, а тянуть web + actuator ради одной проверки - лишний вес. Падение JVM и так
видно по `restart: unless-stopped`; зависание доставки отчёта видно по уведомлениям выше.

## Локальный запуск: БД в Docker, backend локально (например, из IDE)

`.env` автоматически читает только `docker-compose` - IDE/`java -jar`/`./gradlew bootRun` его
не подхватывают. Порядок действий:

1. Поднимите только Postgres: `docker compose up -d postgres` (порт публикуется на хост как
   `POSTGRES_PORT`, по умолчанию `6868` - см. `docker-compose.yml`).
2. Задайте `TELEGRAM_BOT_TOKEN`, `TELEGRAM_SOURCE_CHANNEL_ID`, `TELEGRAM_REPORT_CHAT_ID` как
   переменные окружения в конфигурации запуска IDE (или через плагин "EnvFile", указав на
   `.env`) - без них бот не зарегистрируется в Telegram.
   `SPRING_DATASOURCE_*` задавать не обязательно - дефолты в `application.yml` уже рассчитаны
   на локальный Postgres из docker-compose (`localhost:6868`/`weeklyreport`/`weeklyreport`).
3. Запускайте приложение как обычно (из IDE или `./gradlew bootRun`).

## Как поменять расписание

Расписание не зашито в код: измените `WEEKLY_REPORT_CRON` и/или `WEEKLY_REPORT_TIMEZONE`
в `.env` (или в окружении контейнера) и перезапустите сервис - пересборка не нужна:

```bash
docker compose up -d --no-build
```

Формат cron - как в Spring (`@Scheduled`, 6 полей: секунды минуты часы день-месяца месяц день-недели).

## Как обновить справочники дивизионов и Daribar

Лист "Дивизионы" и резолв `branch_code` по коду Daribar строятся из двух xlsx-справочников,
путь к которым задаётся через `DIVISIONS_FILE_PATH`/`DARIBAR_CROSSWALK_PATH`:

- `divisions.xlsx` - аптека → дивизион → директор (`Адрес Аптеки`, `Код`, `Город`,
  `Нумерация Дивизионера`, `ФИО Дивизионера`);
- `daribar_crosswalk.xlsx` - сопоставление кода аптеки в Daribar с `branch_code`
  (`Код в стандарте`, `Название аптеки в Daribar`, `Код аптеки в Daribar`, `Комментарий`).

Оба файла загружаются **разово при старте приложения** (`ReferenceDataLoader`) с полной
перезаписью соответствующих таблиц (`pharmacy_directory`, `daribar_crosswalk`) - чтобы обновить
данные, отредактируйте файл по указанному пути и **перезапустите приложение**; на лету файлы не
перечитываются. Обе переменные обязательны (без значения по умолчанию в `application.yml`) - без
них приложение не запустится, как и без `TELEGRAM_BOT_TOKEN`. Если путь задан, но по нему нет
файла - в логах будет warning, приложение продолжит работать (лист "Дивизионы" останется пустым,
резолв `branch_code` по коду Daribar не сработает), без падения при старте.

В `docker-compose.yml` оба пути смонтированы из локальной директории `./data` (см. volume
`./data:/data:ro` у сервиса `app`) - положите туда `divisions.xlsx`/`daribar_crosswalk.xlsx`
и перезапустите `docker compose up -d --no-build`.

## Сборка и тесты

```bash
./gradlew clean build
```

Запускает юнит-тесты (парсинг xlsx, генерация отчёта) и интеграционный тест на реальном
PostgreSQL через Testcontainers (нужен запущенный Docker).

Фикстура `src/test/resources/fixtures/monthly_report_test.xlsx` - бинарный файл, сгенерированный
утилитой `TestFixtureGenerator`. Если нужно поменять её структуру - отредактируйте генератор и
перезапустите:

```bash
./gradlew generateTestFixtures
```

## Версии зависимостей

Версии проверены по Maven Central / Central Portal (central.sonatype.com) и официальным
release-страницам на момент сборки, а не взяты по памяти:

| Зависимость                                             | Версия   | Источник |
|----------------------------------------------------------|----------|----------|
| Spring Boot (плагин + BOM)                                | 4.1.0    | spring.io/blog, plugins.gradle.org - последний стабильный релиз |
| Gradle Wrapper                                            | 9.6.1    | services.gradle.org/versions/current |
| io.spring.dependency-management                           | 1.1.7    | plugins.gradle.org |
| org.gradle.toolchains.foojay-resolver-convention           | 1.0.0    | plugins.gradle.org (автозагрузка JDK 21 для toolchain) |
| com.github.ben-manes.versions (`./gradlew dependencyUpdates`) | 0.54.0 | plugins.gradle.org |
| Apache POI (`poi-ooxml`)                                   | 5.5.1    | poi.apache.org - не управляется Spring Boot BOM, версия зафиксирована явно |
| TelegramBots (`telegrambots-springboot-longpolling-starter`, `telegrambots-client`) | 10.0.0 | central.sonatype.com - не управляется Spring Boot BOM |
| PostgreSQL JDBC driver                                     | 42.7.11  | управляется Spring Boot 4.1.0 BOM |
| Flyway (`flyway-core`, через `spring-boot-starter-flyway`)  | 12.4.0   | управляется Spring Boot 4.1.0 BOM. В Spring Boot 4.x автоконфигурация Flyway требует именно `spring-boot-starter-flyway`, одного `flyway-core` недостаточно |
| Testcontainers (`testcontainers-junit-jupiter`, `testcontainers-postgresql`) | 2.2.4 | управляется Spring Boot 4.1.0 BOM. В Testcontainers 2.x артефакты переименованы с префиксом `testcontainers-`, а `PostgreSQLContainer` перестал быть generic-классом и переехал в пакет `org.testcontainers.postgresql` |

Версии PostgreSQL driver, Flyway и Testcontainers сознательно не зафиксированы явно в
`build.gradle.kts` - они наследуются из BOM `spring-boot-dependencies:4.1.0`, что гарантирует
совместимость между собой и с самим Spring Boot.
