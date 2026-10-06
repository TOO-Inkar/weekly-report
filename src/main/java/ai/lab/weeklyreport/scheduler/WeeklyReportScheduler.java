package ai.lab.weeklyreport.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ai.lab.weeklyreport.config.WeeklyReportProperties;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.service.WeeklyReportDeliveryRunner;

/**
 * Раз в неделю (расписание берётся из конфига, а не хардкодится здесь - см. weekly-report.cron/timezone)
 * запускает формирование и отправку недельного отчёта. Повторные попытки при временных сбоях и
 * уведомление о финальном сбое (в Telegram и на почту) - в {@link WeeklyReportDeliveryRunner};
 * пропущенный из-за простоя запуск догоняется при старте - см. {@link WeeklyReportCatchUp}.
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final WeeklyReportDeliveryRunner deliveryRunner;
    private final WeeklyReportProperties properties;
    private final Clock clock;

    public WeeklyReportScheduler(WeeklyReportDeliveryRunner deliveryRunner,
                                  WeeklyReportProperties properties,
                                  Clock clock) {
        this.deliveryRunner = deliveryRunner;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${weekly-report.cron}", zone = "${weekly-report.timezone}")
    public void runWeeklyReport() {
        try {
            // Неделя фиксируется один раз на момент запуска (в таймзоне расписания) - все повторные
            // попытки шлют отчёт именно за неё.
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.timezone())));
            deliveryRunner.deliverWithRetries(WeekRange.containingWeekBefore(today), "запуск по расписанию");
        } catch (Exception e) {
            log.error("Непредвиденная ошибка планового запуска недельного отчёта", e);
        }
    }
}
