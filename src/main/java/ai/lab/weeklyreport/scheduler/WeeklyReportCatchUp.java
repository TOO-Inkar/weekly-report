package ai.lab.weeklyreport.scheduler;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.config.WeeklyReportProperties;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.repository.WeeklyReportDeliveryRepository;
import ai.lab.weeklyreport.service.DeliveryChannel;
import ai.lab.weeklyreport.service.WeeklyReportDeliveryRunner;
import ai.lab.weeklyreport.service.WeeklyReportService;

/**
 * Догоняющий запуск при старте: Spring {@code @Scheduled} не выполняет пропущенные срабатывания
 * cron, поэтому если приложение/хост лежали в момент планового запуска (например, хост
 * перезагрузился в 08:55 и поднялся в 09:30), отчёт за неделю иначе потерялся бы. При старте берём
 * последнее плановое срабатывание за последние 7 дней и, если отчёт за его неделю доставлен не во
 * все каналы (по weekly_report_deliveries), отправляем его - с теми же повторами, что и плановый
 * запуск. Выполняется в отдельном виртуальном потоке, чтобы не задерживать старт приложения.
 * <p>
 * Если журнал доставки пуст (первый запуск после появления таблицы), догоняющий запуск не
 * делается: иначе после деплоя повторно ушёл бы отчёт, уже отправленный до появления журнала.
 */
@Component
public class WeeklyReportCatchUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportCatchUp.class);
    private static final int LOOKBACK_DAYS = 7;

    private final WeeklyReportProperties properties;
    private final WeeklyReportDeliveryProperties deliveryProperties;
    private final WeeklyReportDeliveryRepository deliveryRepository;
    private final WeeklyReportService weeklyReportService;
    private final WeeklyReportDeliveryRunner deliveryRunner;
    private final Clock clock;

    public WeeklyReportCatchUp(WeeklyReportProperties properties,
                               WeeklyReportDeliveryProperties deliveryProperties,
                               WeeklyReportDeliveryRepository deliveryRepository,
                               WeeklyReportService weeklyReportService,
                               WeeklyReportDeliveryRunner deliveryRunner,
                               Clock clock) {
        this.properties = properties;
        this.deliveryProperties = deliveryProperties;
        this.deliveryRepository = deliveryRepository;
        this.weeklyReportService = weeklyReportService;
        this.deliveryRunner = deliveryRunner;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!deliveryProperties.catchUpOnStartup()) {
            log.info("weekly-report.delivery.catch-up-on-startup=false - проверка пропущенного запуска отключена");
            return;
        }
        Thread.ofVirtual().name("weekly-report-catch-up").start(this::catchUpIfMissed);
    }

    /** @return true, если был запущен догоняющий запуск. */
    boolean catchUpIfMissed() {
        try {
            ZoneId zone = ZoneId.of(properties.timezone());
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
            Optional<ZonedDateTime> lastFire = lastScheduledRun(CronExpression.parse(properties.cron()), now);
            if (lastFire.isEmpty()) {
                return false;
            }
            if (!deliveryRepository.hasAnyDelivery()) {
                log.info("Журнал доставки недельных отчётов пуст - догоняющий запуск не выполняется, ждём планового");
                return false;
            }
            WeekRange week = WeekRange.containingWeekBefore(lastFire.get().toLocalDate());
            Set<DeliveryChannel> pending = weeklyReportService.pendingChannels(week);
            if (pending.isEmpty()) {
                log.info("Пропущенных запусков нет: отчёт за {} - {} уже доставлен", week.start(), week.end());
                return false;
            }
            log.warn("Плановый запуск {} не доставил отчёт за {} - {} в {} (приложение было недоступно?) - догоняющий запуск",
                    lastFire.get(), week.start(), week.end(), pending);
            deliveryRunner.deliverWithRetries(week, "догоняющий запуск после рестарта, плановый был " + lastFire.get().toLocalDateTime());
            return true;
        } catch (Exception e) {
            log.error("Ошибка проверки пропущенного запуска недельного отчёта", e);
            return false;
        }
    }

    /** Последнее срабатывание cron не позже {@code now} в пределах {@link #LOOKBACK_DAYS} дней. */
    static Optional<ZonedDateTime> lastScheduledRun(CronExpression cron, ZonedDateTime now) {
        ZonedDateTime candidate = cron.next(now.minusDays(LOOKBACK_DAYS));
        ZonedDateTime last = null;
        while (candidate != null && !candidate.isAfter(now)) {
            last = candidate;
            candidate = cron.next(candidate);
        }
        return Optional.ofNullable(last);
    }
}
