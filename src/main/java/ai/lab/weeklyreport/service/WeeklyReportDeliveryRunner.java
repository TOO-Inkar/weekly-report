package ai.lab.weeklyreport.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.NotifyProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.config.WeeklyReportProperties;
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
 * почте, и наоборот. Уведомление о сбое на почту получают и получатели отчёта, и получатели
 * статус-писем ({@link NotifyProperties}).
 * <p>
 * После каждого цикла, в котором что-то реально отправлялось, получателям статус-писем уходит
 * короткое письмо без вложения: об успехе (каналы, имя файла, время) или о финальном сбое. Если
 * отправлять было нечего (всё уже доставлено, например при догоняющем запуске) - письма нет. Сбой
 * отправки статус-письма только логируется и не влияет на доставку и журнал.
 * <p>
 * Ожидание между попытками блокирует вызывающий поток - это нормально, т.к. включены virtual
 * threads (spring.threads.virtual.enabled), и @Scheduled-задачи выполняются каждая в своём
 * виртуальном потоке, не задерживая IngestionWatchdog. Запуски сериализуются замком: плановый
 * запуск и догоняющий запуск после рестарта не идут одновременно.
 */
@Component
public class WeeklyReportDeliveryRunner {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportDeliveryRunner.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
    private final NotifyProperties notifyProperties;
    private final ZoneId zone;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ReentrantLock runLock = new ReentrantLock();

    @Autowired
    public WeeklyReportDeliveryRunner(WeeklyReportService weeklyReportService,
                                      TelegramSender telegramSender,
                                      TelegramProperties telegramProperties,
                                      EmailSender emailSender,
                                      EmailProperties emailProperties,
                                      WeeklyReportDeliveryProperties deliveryProperties,
                                      NotifyProperties notifyProperties,
                                      WeeklyReportProperties weeklyReportProperties,
                                      Clock clock) {
        this(weeklyReportService, telegramSender, telegramProperties, emailSender, emailProperties, deliveryProperties,
                notifyProperties, ZoneId.of(weeklyReportProperties.timezone()), clock, duration -> Thread.sleep(duration));
    }

    WeeklyReportDeliveryRunner(WeeklyReportService weeklyReportService,
                               TelegramSender telegramSender,
                               TelegramProperties telegramProperties,
                               EmailSender emailSender,
                               EmailProperties emailProperties,
                               WeeklyReportDeliveryProperties deliveryProperties,
                               NotifyProperties notifyProperties,
                               ZoneId zone,
                               Clock clock,
                               Sleeper sleeper) {
        this.weeklyReportService = weeklyReportService;
        this.telegramSender = telegramSender;
        this.telegramProperties = telegramProperties;
        this.emailSender = emailSender;
        this.emailProperties = emailProperties;
        this.deliveryProperties = deliveryProperties;
        this.notifyProperties = notifyProperties;
        this.zone = zone;
        this.clock = clock;
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
        // Накопительно по всем попыткам: канал, доставленный на 1-й попытке, на следующих уже пропускается.
        Set<DeliveryChannel> deliveredThisCycle = EnumSet.noneOf(DeliveryChannel.class);
        String fileName = null;
        int attempt = 1;

        for (; attempt <= maxAttempts; attempt++) {
            try {
                WeeklyReportService.DeliveryResult result = weeklyReportService.deliver(week);
                deliveredThisCycle.addAll(result.delivered());
                if (result.fileName() != null) {
                    fileName = result.fileName();
                }
                if (result.isComplete()) {
                    if (attempt > 1) {
                        log.info("Недельный отчёт за {} - {} доставлен с попытки {}/{} ({})",
                                week.start(), week.end(), attempt, maxAttempts, trigger);
                    }
                    if (!deliveredThisCycle.isEmpty()) {
                        sendSuccessStatus(week, trigger, attempt, deliveredThisCycle, fileName);
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
        notifyFinalFailure(week, trigger, attemptsMade, buildFailure, channelFailures, deliveredThisCycle);
        return false;
    }

    private void sendSuccessStatus(WeekRange week, String trigger, int attempts, Set<DeliveryChannel> delivered,
                                   String fileName) {
        String body = """
                Недельный отчёт за %s - %s успешно отправлен.
                Доставлен в: %s
                Файл: %s
                Время: %s (%s)
                Запуск: %s, попыток: %d
                """.formatted(week.start(), week.end(), channelList(delivered), fileName,
                ZonedDateTime.now(clock.withZone(zone)).format(TIME_FORMAT), zone, trigger, attempts);
        sendStatusEmail(notifyProperties.recipients(),
                "Недельный отчёт за %s - %s успешно отправлен".formatted(week.start(), week.end()), body);
    }

    /** Статус-письмо - лучшая попытка: ошибка отправки только логируется. */
    private void sendStatusEmail(List<String> recipients, String subject, String body) {
        if (recipients.isEmpty()) {
            return;
        }
        try {
            emailSender.sendNotification(recipients, subject, body);
            log.info("Статус-письмо '{}' отправлено: {}", subject, recipients);
        } catch (Exception e) {
            log.error("Не удалось отправить статус-письмо '{}' получателям {}", subject, recipients, e);
        }
    }

    private void notifyFinalFailure(WeekRange week, String trigger, int attempts, Exception buildFailure,
                                    Map<DeliveryChannel, Exception> channelFailures,
                                    Set<DeliveryChannel> deliveredThisCycle) {
        StringBuilder text = new StringBuilder()
                .append("Недельный отчёт за %s - %s не доставлен (%s, попыток: %d).\n"
                        .formatted(week.start(), week.end(), trigger, attempts));
        if (buildFailure != null) {
            text.append("Ошибка формирования отчёта: ").append(buildFailure.getMessage()).append('\n');
        }
        channelFailures.forEach((channel, e) -> text
                .append(channelLabel(channel)).append(": ").append(e.getMessage()).append('\n'));
        if (!deliveredThisCycle.isEmpty()) {
            text.append("Успешно доставлен в: ").append(channelList(deliveredThisCycle)).append('\n');
        }
        text.append("Ещё одна попытка будет при следующем перезапуске приложения (догоняющий запуск). ")
                .append("Подробности - в логах приложения.");
        String message = text.toString();

        try {
            telegramSender.sendMessage(telegramProperties.reportChatId(), message);
        } catch (Exception e) {
            log.error("Не удалось отправить уведомление о сбое недельного отчёта в Telegram", e);
        }

        // Одно письмо на объединённый список (получатели отчёта + статус-получатели), без дублей адресов.
        Set<String> recipients = new LinkedHashSet<>(emailProperties.recipients());
        recipients.addAll(notifyProperties.recipients());
        if (recipients.isEmpty()) {
            if (buildFailure != null || channelFailures.containsKey(DeliveryChannel.TELEGRAM)) {
                log.error("Получатели email не заданы (WEEKLY_REPORT_EMAIL_RECIPIENTS/WEEKLY_REPORT_NOTIFY_RECIPIENTS) - уведомить о сбое по почте некого");
            }
            return;
        }
        sendStatusEmail(List.copyOf(recipients),
                "Недельный отчёт за %s - %s не доставлен".formatted(week.start(), week.end()), message);
    }

    private static String channelList(Set<DeliveryChannel> channels) {
        return channels.stream().map(WeeklyReportDeliveryRunner::channelLabel).collect(Collectors.joining(", "));
    }

    private static String channelLabel(DeliveryChannel channel) {
        return switch (channel) {
            case TELEGRAM -> "Telegram";
            case EMAIL -> "Email";
        };
    }
}
