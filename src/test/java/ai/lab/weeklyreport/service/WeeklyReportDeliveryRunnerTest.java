package ai.lab.weeklyreport.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.email.EmailSender;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.telegram.TelegramSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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
    private static final WeekRange WEEK = new WeekRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));

    private final WeeklyReportService service = mock(WeeklyReportService.class);
    private final TelegramSender telegramSender = mock(TelegramSender.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private final WeeklyReportDeliveryProperties properties =
            new WeeklyReportDeliveryProperties(4, Duration.ofMinutes(2), Duration.ofMinutes(5), true);

    private WeeklyReportDeliveryRunner runner(List<String> recipients) {
        return new WeeklyReportDeliveryRunner(service, telegramSender, new TelegramProperties("token", "source", REPORT_CHAT_ID),
                emailSender, new EmailProperties(recipients), properties, sleeps::add);
    }

    private static WeeklyReportService.DeliveryResult ok() {
        return new WeeklyReportService.DeliveryResult(Set.of(DeliveryChannel.TELEGRAM), Map.of());
    }

    private static WeeklyReportService.DeliveryResult failed(DeliveryChannel channel, String reason) {
        return new WeeklyReportService.DeliveryResult(Set.of(), Map.of(channel, new RuntimeException(reason)));
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
        WeeklyReportDeliveryRunner interrupted = new WeeklyReportDeliveryRunner(service, telegramSender,
                new TelegramProperties("token", "source", REPORT_CHAT_ID), emailSender, new EmailProperties(RECIPIENTS),
                properties, d -> {
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
}
