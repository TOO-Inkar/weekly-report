-- Журнал доставки недельного отчёта по каналам (TELEGRAM/EMAIL). Ключ идемпотентности
-- (week_start, channel): повторные попытки и догоняющий запуск после рестарта отправляют отчёт
-- только в те каналы, куда он за эту неделю ещё не ушёл (см. WeeklyReportService.deliver).
CREATE TABLE weekly_report_deliveries (
    week_start   DATE        NOT NULL,
    channel      VARCHAR(16) NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (week_start, channel)
);
