package ai.lab.weeklyreport.service;

import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.email.EmailSender;
import ai.lab.weeklyreport.excel.DivisionsReportData;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.excel.WeeklyReportGenerator;
import ai.lab.weeklyreport.metric.MetricDailyTotal;
import ai.lab.weeklyreport.repository.DailyMetricRepository;
import ai.lab.weeklyreport.repository.DivisionReportRepository;
import ai.lab.weeklyreport.repository.PharmacyDirectoryRepository;
import ai.lab.weeklyreport.repository.WeeklyReportDeliveryRepository;
import ai.lab.weeklyreport.telegram.TelegramSender;

/**
 * Собирает недельный отчёт (пн-вс) из БД и отправляет его в Telegram-чат и на почту. Повторные
 * попытки и уведомления о финальном сбое - в {@link WeeklyReportDeliveryRunner}.
 */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    private static final DateTimeFormatter FILE_DAY_MONTH_FORMAT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter FILE_DAY_FORMAT = DateTimeFormatter.ofPattern("dd");

    private final DailyMetricRepository dailyMetricRepository;
    private final DivisionReportRepository divisionReportRepository;
    private final PharmacyDirectoryRepository pharmacyDirectoryRepository;
    private final WeeklyReportGenerator generator;
    private final TelegramSender telegramSender;
    private final TelegramProperties telegramProperties;
    private final EmailSender emailSender;
    private final EmailProperties emailProperties;
    private final WeeklyReportDeliveryRepository deliveryRepository;

    public WeeklyReportService(DailyMetricRepository dailyMetricRepository,
                                DivisionReportRepository divisionReportRepository,
                                PharmacyDirectoryRepository pharmacyDirectoryRepository,
                                WeeklyReportGenerator generator,
                                TelegramSender telegramSender,
                                TelegramProperties telegramProperties,
                                EmailSender emailSender,
                                EmailProperties emailProperties,
                                WeeklyReportDeliveryRepository deliveryRepository) {
        this.dailyMetricRepository = dailyMetricRepository;
        this.divisionReportRepository = divisionReportRepository;
        this.pharmacyDirectoryRepository = pharmacyDirectoryRepository;
        this.generator = generator;
        this.telegramSender = telegramSender;
        this.telegramProperties = telegramProperties;
        this.emailSender = emailSender;
        this.emailProperties = emailProperties;
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * Каналы, куда отчёт за неделю ещё не доставлен: Telegram - всегда, email - только если задан
     * хотя бы один получатель.
     */
    public Set<DeliveryChannel> pendingChannels(WeekRange week) {
        Set<DeliveryChannel> pending = EnumSet.of(DeliveryChannel.TELEGRAM);
        if (!emailProperties.recipients().isEmpty()) {
            pending.add(DeliveryChannel.EMAIL);
        }
        pending.removeAll(deliveryRepository.findDeliveredChannels(week.start()));
        return pending;
    }

    /**
     * Одна попытка: собирает отчёт за {@code week} и отправляет его в каналы, куда он ещё не ушёл
     * (идемпотентно - см. weekly_report_deliveries). Сбой сборки (например, недоступна БД)
     * пробрасывается исключением; сбой отдельного канала не мешает остальным и возвращается в
     * {@link DeliveryResult#failures()}, чтобы вызывающий мог повторить попытку.
     */
    public DeliveryResult deliver(WeekRange week) {
        Set<DeliveryChannel> pending = pendingChannels(week);
        if (pending.isEmpty()) {
            log.info("Недельный отчёт за {} - {} уже доставлен во все каналы, повторная отправка не нужна",
                    week.start(), week.end());
            return new DeliveryResult(Set.of(), Map.of(), null);
        }

        WeekRange previousWeek = week.previousWeek();
        List<MetricDailyTotal> currentTotals = dailyMetricRepository.findDailyTotals(week.start(), week.end());
        List<MetricDailyTotal> previousTotals = dailyMetricRepository.findDailyTotals(previousWeek.start(), previousWeek.end());

        // Лист "Дивизионы" показывает только текущую неделю, без сравнения с прошлой.
        DivisionsReportData divisionsData = new DivisionsReportData(
                pharmacyDirectoryRepository.findDivisionRegistry(),
                divisionReportRepository.findMetricTotalsByDivision(week.start(), week.end()),
                divisionReportRepository.findActivityTotalsByDivision(week.start(), week.end()));

        byte[] workbook = generator.generate(week, currentTotals, previousWeek, previousTotals, divisionsData);

        String fileName = "Недельный отчет по ПЛ и маркетплейсам (%s).xlsx".formatted(fileNameRange(week));
        String caption = "Недельный отчёт: %s - %s".formatted(week.start(), week.end());

        Set<DeliveryChannel> delivered = EnumSet.noneOf(DeliveryChannel.class);
        Map<DeliveryChannel, Exception> failures = new EnumMap<>(DeliveryChannel.class);

        if (pending.contains(DeliveryChannel.TELEGRAM)) {
            try {
                log.info("Отправка недельного отчёта в Telegram: fileName='{}'", fileName);
                telegramSender.sendDocument(telegramProperties.reportChatId(), fileName, workbook, caption);
                markDelivered(week, DeliveryChannel.TELEGRAM);
                delivered.add(DeliveryChannel.TELEGRAM);
            } catch (Exception e) {
                log.warn("Отчёт за {} - {} не отправлен в Telegram: {}", week.start(), week.end(), e.toString());
                failures.put(DeliveryChannel.TELEGRAM, e);
            }
        }

        // Email отправляется независимо от результата Telegram: если упал один канал, отчёт всё
        // равно должен дойти хотя бы по другому.
        if (pending.contains(DeliveryChannel.EMAIL)) {
            try {
                log.info("Отправка недельного отчёта на почту: получатели={}", emailProperties.recipients());
                emailSender.sendReport(emailProperties.recipients(), fileName, caption, fileName, workbook);
                markDelivered(week, DeliveryChannel.EMAIL);
                delivered.add(DeliveryChannel.EMAIL);
            } catch (Exception e) {
                // Причина сбоя (авторизация/SSL/таймаут/отклонённый адрес) уже залогирована с
                // деталями внутри EmailSender.
                log.warn("Отчёт за {} - {} не отправлен на почту: {}", week.start(), week.end(), e.toString());
                failures.put(DeliveryChannel.EMAIL, e);
            }
        }
        return new DeliveryResult(delivered, failures, fileName);
    }

    /**
     * Отметка о доставке пишется уже после успешной отправки. Если именно она не удалась (БД
     * отвалилась между отправкой и записью), канал всё равно считается доставленным в этой попытке -
     * повторять отправку ради отметки хуже (дубль отчёта получателям); риск только в том, что
     * догоняющий запуск после рестарта может отправить этот канал ещё раз.
     */
    private void markDelivered(WeekRange week, DeliveryChannel channel) {
        try {
            deliveryRepository.markDelivered(week.start(), channel);
        } catch (Exception e) {
            log.error("Отчёт за {} отправлен в {}, но отметку о доставке записать в БД не удалось", week.start(), channel, e);
        }
    }

    /**
     * Итог одной попытки: куда отчёт ушёл сейчас, какие каналы упали (с причиной) и имя файла
     * отчёта ({@code null}, если отправлять было нечего - всё уже доставлено раньше).
     */
    public record DeliveryResult(Set<DeliveryChannel> delivered, Map<DeliveryChannel, Exception> failures,
                                 String fileName) {

        public boolean isComplete() {
            return failures.isEmpty();
        }
    }

    /** "13-19.07" в пределах одного месяца (как в эталонном файле), иначе "27.07-02.08". */
    private static String fileNameRange(WeekRange week) {
        if (week.start().getMonth() == week.end().getMonth()) {
            return "%s-%s".formatted(week.start().format(FILE_DAY_FORMAT), week.end().format(FILE_DAY_MONTH_FORMAT));
        }
        return "%s-%s".formatted(week.start().format(FILE_DAY_MONTH_FORMAT), week.end().format(FILE_DAY_MONTH_FORMAT));
    }
}
