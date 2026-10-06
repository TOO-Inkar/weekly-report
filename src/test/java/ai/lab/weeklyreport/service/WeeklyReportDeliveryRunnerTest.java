package ai.lab.weeklyreport.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.NotifyProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.email.EmailSender;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.telegram.TelegramSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeeklyReportDeliveryRunnerTest {

    private static final String REPORT_CHAT_ID = "report-chat";
    private static final List<String> RECIPIENTS = List.of("boss@example.kz");
    private static final List<String> STATUS = List.of("it@inkar.kz", "m.muratbekuly@inkar.kz");
    private static final String FILE_NAME = "Недельный отчет по ПЛ и маркетплейсам (28.09-04.10).xlsx";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZoneOffset.UTC);
    private static final WeekRange WEEK = new WeekRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));

    private final WeeklyReportService service = mock(WeeklyReportService.class);
    private final TelegramSender telegramSender = mock(TelegramSender.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private final WeeklyReportDeliveryProperties properties =
            new WeeklyReportDeliveryProperties(4, Duration.ofMinutes(2), Duration.ofMinutes(5), true);

    private WeeklyReportDeliveryRunner runner(List<String> recipients) {
        return runner(recipients, List.of(), sleeps::add);
    }

    private WeeklyReportDeliveryRunner runner(List<String> recipients, List<String> statusRecipients,
                                              WeeklyReportDeliveryRunner.Sleeper sleeper) {
        return new WeeklyReportDeliveryRunner(service, telegramSender, new TelegramProperties("token", "source", REPORT_CHAT_ID),
                emailSender, new EmailProperties(recipients), properties, new NotifyProperties(statusRecipients),
                ZoneId.of("Asia/Almaty"), CLOCK, sleeper);
    }

    private static WeeklyReportService.DeliveryResult ok() {
        return new WeeklyReportService.DeliveryResult(Set.of(DeliveryChannel.TELEGRAM), Map.of(), FILE_NAME);
    }

    private static WeeklyReportService.DeliveryResult failed(DeliveryChannel channel, String reason) {
        return new WeeklyReportService.DeliveryResult(Set.of(), Map.of(channel, new RuntimeException(reason)), FILE_NAME);
    }

    @Test
    void firstAttemptSuccessDoesNotSleepOrNotify() {
        when(service.deliver(WEEK)).thenReturn(ok());

        assertThat(runner(RECIPIENTS).deliverWithRetries(WEEK, "test")).isTrue();

        assertThat(sleeps).isEmpty();
        verifyNoInteractions(telegramSender, emailSender);
    }

    @Test
    void retriesTransientFailuresWithBackoffUntilSuccess() {
        when(service.deliver(WEEK))
                .thenThrow(new RuntimeException("Connection refused"))
                .thenReturn(failed(DeliveryChannel.TELEGRAM, "UnknownHostException: api.telegram.org"))
                .thenReturn(ok());

        assertThat(runner(RECIPIENTS).deliverWithRetries(WEEK, "test")).isTrue();

        verify(service, times(3)).deliver(WEEK);
        assertThat(sleeps).containsExactly(Duration.ofMinutes(2), Duration.ofMinutes(4));
        verifyNoInteractions(telegramSender, emailSender);
    }

    @Test
    void telegramFailureAfterAllAttemptsNotifiesByEmail() throws Exception {
        when(service.deliver(WEEK)).thenReturn(failed(DeliveryChannel.TELEGRAM, "api.telegram.org недоступен"));
        doThrow(new TelegramApiException("still down")).when(telegramSender).sendMessage(anyString(), anyString());

        assertThat(runner(RECIPIENTS).deliverWithRetries(WEEK, "test")).isFalse();

        verify(service, times(4)).deliver(WEEK);
        // Паузы только между попытками (не после последней), с потолком maxBackoff.
        assertThat(sleeps).containsExactly(Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(5));
        verify(emailSender).sendNotification(eq(RECIPIENTS), contains("2026-09-28"), contains("api.telegram.org недоступен"));
    }

    @Test
    void emailFailureAfterAllAttemptsNotifiesTelegram() throws Exception {
        when(service.deliver(WEEK)).thenReturn(failed(DeliveryChannel.EMAIL, "SMTP: таймаут"));

        assertThat(runner(RECIPIENTS).deliverWithRetries(WEEK, "test")).isFalse();

        verify(telegramSender).sendMessage(eq(REPORT_CHAT_ID), contains("Email: SMTP: таймаут"));
    }

    @Test
    void buildFailureNotifiesBothChannels() throws Exception {
        when(service.deliver(WEEK)).thenThrow(new RuntimeException("Connection to postgres refused"));

        assertThat(runner(RECIPIENTS).deliverWithRetries(WEEK, "test")).isFalse();

        verify(telegramSender).sendMessage(eq(REPORT_CHAT_ID), contains("Connection to postgres refused"));
        verify(emailSender).sendNotification(eq(RECIPIENTS), anyString(), contains("Connection to postgres refused"));
    }

    @Test
    void withoutRecipientsOnlyTelegramIsNotified() throws Exception {
        when(service.deliver(WEEK)).thenReturn(failed(DeliveryChannel.TELEGRAM, "down"));

        runner(List.of()).deliverWithRetries(WEEK, "test");

        verify(telegramSender).sendMessage(eq(REPORT_CHAT_ID), anyString());
        verify(emailSender, times(0)).sendNotification(anyList(), anyString(), anyString());
    }

    @Test
    void interruptionStopsRetriesAndStillNotifies() throws Exception {
        when(service.deliver(WEEK)).thenReturn(failed(DeliveryChannel.TELEGRAM, "down"));
        WeeklyReportDeliveryRunner interrupted = runner(RECIPIENTS, List.of(), d -> {
            throw new InterruptedException();
        });

        assertThat(interrupted.deliverWithRetries(WEEK, "test")).isFalse();
        assertThat(Thread.interrupted()).isTrue(); // флаг восстановлен (и сброшен здесь для других тестов)

        verify(service, times(1)).deliver(WEEK);
        verify(emailSender).sendNotification(eq(RECIPIENTS), anyString(), contains("попыток: 1"));
    }

    @Test
    void defaultBackoffScheduleSpansAboutNinetyMinutes() {
        WeeklyReportDeliveryProperties defaults = new WeeklyReportDeliveryProperties(null, null, null, null);

        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < defaults.maxAttempts(); attempt++) {
            total = total.plus(defaults.backoffAfter(attempt));
        }

        assertThat(defaults.maxAttempts()).isEqualTo(7);
        assertThat(defaults.catchUpOnStartup()).isTrue();
        assertThat(total).isEqualTo(Duration.ofMinutes(2 + 4 + 8 + 16 + 30 + 30));
    }

    @Test
    void successSendsStatusEmailWithChannelsFileAndTime() throws Exception {
        when(service.deliver(WEEK)).thenReturn(ok());

        assertThat(runner(List.of(), STATUS, sleeps::add).deliverWithRetries(WEEK, "запуск по расписанию")).isTrue();

        verify(emailSender).sendNotification(eq(STATUS), contains("успешно отправлен"), argThat(body ->
                body.contains("2026-09-28 - 2026-10-04 успешно отправлен")
                        && body.contains("Доставлен в: Telegram")
                        && body.contains(FILE_NAME)
                        && body.contains("2026-10-05 09:00")));
        verifyNoInteractions(telegramSender);
    }

    @Test
    void successAfterRetryListsChannelsDeliveredAcrossAttempts() throws Exception {
        when(service.deliver(WEEK))
                .thenReturn(new WeeklyReportService.DeliveryResult(Set.of(DeliveryChannel.EMAIL),
                        Map.of(DeliveryChannel.TELEGRAM, new RuntimeException("down")), FILE_NAME))
                .thenReturn(ok());

        runner(RECIPIENTS, STATUS, sleeps::add).deliverWithRetries(WEEK, "test");

        verify(emailSender).sendNotification(eq(STATUS), anyString(), contains("Доставлен в: Telegram, Email"));
    }

    @Test
    void nothingToDeliverSendsNoStatusEmail() throws Exception {
        when(service.deliver(WEEK)).thenReturn(new WeeklyReportService.DeliveryResult(Set.of(), Map.of(), null));

        assertThat(runner(List.of(), STATUS, sleeps::add).deliverWithRetries(WEEK, "догоняющий запуск")).isTrue();

        verifyNoInteractions(emailSender, telegramSender);
    }

    @Test
    void finalFailureEmailGoesToStatusRecipientsEvenWithoutReportRecipients() throws Exception {
        when(service.deliver(WEEK)).thenReturn(failed(DeliveryChannel.TELEGRAM, "api.telegram.org недоступен"));

        assertThat(runner(List.of(), STATUS, sleeps::add).deliverWithRetries(WEEK, "test")).isFalse();

        verify(emailSender).sendNotification(eq(STATUS), contains("не доставлен"), contains("api.telegram.org недоступен"));
        verify(telegramSender).sendMessage(eq(REPORT_CHAT_ID), anyString());
    }

    @Test
    void finalFailureEmailMergesRecipientListsWithoutDuplicates() throws Exception {
        when(service.deliver(WEEK)).thenThrow(new RuntimeException("db down"));

        runner(List.of("boss@example.kz", "it@inkar.kz"), STATUS, sleeps::add).deliverWithRetries(WEEK, "test");

        verify(emailSender, times(1)).sendNotification(
                eq(List.of("boss@example.kz", "it@inkar.kz", "m.muratbekuly@inkar.kz")), anyString(), anyString());
    }

    @Test
    void statusEmailFailureDoesNotAffectDeliveryOutcome() throws Exception {
        when(service.deliver(WEEK)).thenReturn(ok());
        doThrow(new org.springframework.mail.MailSendException("SMTP down"))
                .when(emailSender).sendNotification(anyList(), anyString(), anyString());

        assertThat(runner(List.of(), STATUS, sleeps::add).deliverWithRetries(WEEK, "test")).isTrue();

        verify(service, times(1)).deliver(WEEK);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void notifyRecipientsAreTrimmedAndBlanksDropped() {
        assertThat(new NotifyProperties(List.of(" it@inkar.kz", "", " ")).recipients()).containsExactly("it@inkar.kz");
        assertThat(new NotifyProperties(null).recipients()).isEmpty();
    }
}
