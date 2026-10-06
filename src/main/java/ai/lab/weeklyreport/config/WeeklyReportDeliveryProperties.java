package ai.lab.weeklyreport.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Устойчивость доставки недельного отчёта к временным сбоям (Telegram/SMTP/БД недоступны ровно в
 * момент запуска): сколько всего попыток, пауза перед второй попыткой (дальше удваивается, но не
 * больше maxBackoff) и делать ли догоняющий запуск при старте, если плановый запуск был пропущен
 * (приложение/хост лежали в момент cron). По умолчанию 7 попыток: паузы 2, 4, 8, 16, 30, 30 мин -
 * всего ~1.5 часа.
 */
@ConfigurationProperties(prefix = "weekly-report.delivery")
public record WeeklyReportDeliveryProperties(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff,
                                             Boolean catchUpOnStartup) {

    public WeeklyReportDeliveryProperties {
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 7 : maxAttempts;
        initialBackoff = initialBackoff == null ? Duration.ofMinutes(2) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(30) : maxBackoff;
        catchUpOnStartup = catchUpOnStartup == null || catchUpOnStartup;
    }

    /** Пауза после неудачной попытки номер {@code attempt} (с 1): initialBackoff * 2^(attempt-1), не больше maxBackoff. */
    public Duration backoffAfter(int attempt) {
        Duration delay = initialBackoff;
        for (int i = 1; i < attempt && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
