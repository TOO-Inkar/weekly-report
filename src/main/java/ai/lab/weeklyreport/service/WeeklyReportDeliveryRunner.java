package ai.lab.weeklyreport.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.email.EmailSender;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.telegram.TelegramSender;

/**
 * Доставка недельного отчёта с повторными попытками: если в момент запуска временно недоступны
 * Telegram, SMTP или БД (например, сразу после перезагрузки хоста ещё не поднялись сеть/DNS),
 * отчёт не теряется, а отправляется повторно с нарастающей паузой (см.
 * {@link WeeklyReportDeliveryProperties}). Повторы идемпотентны - уже доставленные каналы
 * пропускаются (см. {@link WeeklyReportService#deliver}).
 * <p>
 * Если все попытки исчерпаны - уведомление о сбое уходит в оба канала (Telegram-чат отчётов и
 * получателям email), каждый по возможности: если не работает Telegram, о проблеме узнают по
 * почте, и наоборот.
 * <p>
 * Ожидание между попытками блокирует вызывающий поток - это нормально, т.к. включены virtual
 * threads (spring.threads.virtual.enabled), и @Scheduled-задачи выполняются каждая в своём
 * виртуальном потоке, не задерживая IngestionWatchdog. Запуски сериализуются замком: плановый
 * запуск и догоняющий запуск после рестарта не идут одновременно.
 */
@Component
public class WeeklyReportDeliveryRunner {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportDeliveryRunner.class);

    /** Пауза между попытками; выделено в интерфейс, чтобы тесты не ждали по-настоящему. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final WeeklyReportService weeklyReportService;
    private final TelegramSender telegramSender;
    private final TelegramProperties telegramProperties;
    private final EmailSender emailSender;
    private final EmailProperties emailProperties;
    private final WeeklyReportDeliveryProperties deliveryProperties;
    private final Sleeper sleeper;
    private final ReentrantLock runLock = new ReentrantLock();

    @Autowired
    public WeeklyReportDeliveryRunner(WeeklyReportService weeklyReportService,
                                      TelegramSender telegramSender,
                                      TelegramProperties telegramProperties,
                                      EmailSender emailSender,
                                      EmailProperties emailProperties,
                                      WeeklyReportDeliveryProperties deliveryProperties) {
        this(weeklyReportService, telegramSender, telegramProperties, emailSender, emailProperties, deliveryProperties,
                duration -> Thread.sleep(duration));
    }

    WeeklyReportDeliveryRunner(WeeklyReportService weeklyReportService,
                               TelegramSender telegramSender,
                               TelegramProperties telegramProperties,
                               EmailSender emailSender,
                               EmailProperties emailProperties,
                               WeeklyReportDeliveryProperties deliveryProperties,
                               Sleeper sleeper) {
        this.weeklyReportService = weeklyReportService;
        this.telegramSender = telegramSender;
        this.telegramProperties = telegramProperties;
        this.emailSender = emailSender;
        this.emailProperties = emailProperties;
        this.deliveryProperties = deliveryProperties;
        this.sleeper = sleeper;
    }

    /**
     * @param trigger человекочитаемая причина запуска для логов/уведомлений ("по расписанию" и т.п.)
     * @return true, если отчёт в итоге доставлен во все каналы (или уже был доставлен раньше)
     */
    public boolean deliverWithRetries(WeekRange week, String trigger) {
        runLock.lock();
        try {
            return doDeliverWithRetries(week, trigger);
        } finally {
            runLock.unlock();
        }
    }

    private boolean doDeliverWithRetries(WeekRange week, String trigger) {
        int maxAttempts = deliveryProperties.maxAttempts();
        Exception buildFailure = null;
        Map<DeliveryChannel, Exception> channelFailures = Map.of();
        int attempt = 1;

        for (; attempt <= maxAttempts; attempt++) {
            try {
                WeeklyReportService.DeliveryResult result = weeklyReportService.deliver(week);
                if (result.isComplete()) {
                    if (attempt > 1) {
                        log.info("Недельный отчёт за {} - {} доставлен с попытки {}/{} ({})",
                                week.start(), week.end(), attempt, maxAttempts, trigger);
                    }
                    return true;
                }
                buildFailure = null;
                channelFailures = result.failures();
                log.warn("Попытка {}/{} доставки недельного отчёта за {} - {} ({}): не доставлено в {}",
                        attempt, maxAttempts, week.start(), week.end(), trigger, channelFailures.keySet());
            } catch (Exception e) {
                buildFailure = e;
                channelFailures = Map.of();
                log.warn("Попытка {}/{} формирования недельного отчёта за {} - {} ({}) не удалась",
                        attempt, maxAttempts, week.start(), week.end(), trigger, e);
            }

            if (attempt < maxAttempts) {
                Duration delay = deliveryProperties.backoffAfter(attempt);
                log.info("Следующая попытка доставки недельного отчёта через {} мин.", delay.toMinutes());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Повторные попытки доставки недельного отчёта прерваны (остановка приложения?)");
                    break;
                }
            }
        }

        int attemptsMade = Math.min(attempt, maxAttempts);
        if (buildFailure != null) {
            log.error("Не удалось сформировать недельный отчёт за {} - {} после {} попыток ({})",
                    week.start(), week.end(), attemptsMade, trigger, buildFailure);
        } else {
            channelFailures.forEach((channel, e) -> log.error(
                    "Не удалось доставить недельный отчёт за {} - {} в {} после {} попыток ({})",
                    week.start(), week.end(), channel, attemptsMade, trigger, e));
        }
        notifyFinalFailure(week, trigger, attemptsMade, buildFailure, channelFailures);
        return false;
    }

    private void notifyFinalFailure(WeekRange week, String trigger, int attempts, Exception buildFailure,
                                    Map<DeliveryChannel, Exception> channelFailures) {
        StringBuilder text = new StringBuilder()
                .append("Недельный отчёт за %s - %s не доставлен (%s, попыток: %d).\n"
                        .formatted(week.start(), week.end(), trigger, attempts));
        if (buildFailure != null) {
            text.append("Ошибка формирования отчёта: ").append(buildFailure.getMessage()).append('\n');
        }
        channelFailures.forEach((channel, e) -> text
                .append(channelLabel(channel)).append(": ").append(e.getMessage()).append('\n'));
        text.append("Ещё одна попытка будет при следующем перезапуске приложения (догоняющий запуск). ")
                .append("Подробности - в логах приложения.");
        String message = text.toString();

        try {
            telegramSender.sendMessage(telegramProperties.reportChatId(), message);
        } catch (Exception e) {
            log.error("Не удалось отправить уведомление о сбое недельного отчёта в Telegram", e);
        }

        if (emailProperties.recipients().isEmpty()) {
            if (buildFailure != null || channelFailures.containsKey(DeliveryChannel.TELEGRAM)) {
                log.error("Получатели email не заданы (WEEKLY_REPORT_EMAIL_RECIPIENTS) - уведомить о сбое по почте некого");
            }
            return;
        }
        try {
            emailSender.sendNotification(emailProperties.recipients(),
                    "Недельный отчёт за %s - %s не доставлен".formatted(week.start(), week.end()), message);
        } catch (Exception e) {
            log.error("Не удалось отправить уведомление о сбое недельного отчёта на почту", e);
        }
    }

    private static String channelLabel(DeliveryChannel channel) {
        return switch (channel) {
            case TELEGRAM -> "Telegram";
            case EMAIL -> "Email";
        };
    }
}
