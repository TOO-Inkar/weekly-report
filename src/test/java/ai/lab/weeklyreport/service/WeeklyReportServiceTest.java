package ai.lab.weeklyreport.service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.TelegramProperties;
import ai.lab.weeklyreport.email.EmailSender;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.excel.WeeklyReportGenerator;
import ai.lab.weeklyreport.repository.DailyMetricRepository;
import ai.lab.weeklyreport.repository.DivisionReportRepository;
import ai.lab.weeklyreport.repository.PharmacyDirectoryRepository;
import ai.lab.weeklyreport.repository.WeeklyReportDeliveryRepository;
import ai.lab.weeklyreport.telegram.TelegramSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeeklyReportServiceTest {

    private static final String REPORT_CHAT_ID = "report-chat";
    private static final List<String> RECIPIENTS = List.of("boss@example.kz");
    private static final WeekRange WEEK = new WeekRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));
    private static final byte[] WORKBOOK = {1, 2, 3};

    private final DailyMetricRepository dailyMetricRepository = mock(DailyMetricRepository.class);
    private final DivisionReportRepository divisionReportRepository = mock(DivisionReportRepository.class);
    private final PharmacyDirectoryRepository pharmacyDirectoryRepository = mock(PharmacyDirectoryRepository.class);
    private final WeeklyReportGenerator generator = mock(WeeklyReportGenerator.class);
    private final TelegramSender telegramSender = mock(TelegramSender.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final WeeklyReportDeliveryRepository deliveryRepository = mock(WeeklyReportDeliveryRepository.class);

    @BeforeEach
    void setUp() {
        when(generator.generate(any(), any(), any(), any(), any())).thenReturn(WORKBOOK);
        when(deliveryRepository.findDeliveredChannels(WEEK.start())).thenReturn(EnumSet.noneOf(DeliveryChannel.class));
    }

    private WeeklyReportService service(List<String> recipients) {
        return new WeeklyReportService(dailyMetricRepository, divisionReportRepository, pharmacyDirectoryRepository,
                generator, telegramSender, new TelegramProperties("token", "source", REPORT_CHAT_ID), emailSender,
                new EmailProperties(recipients), deliveryRepository);
    }

    @Test
    void deliversToBothChannelsAndRecordsDeliveries() throws Exception {
        WeeklyReportService.DeliveryResult result = service(RECIPIENTS).deliver(WEEK);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.delivered()).containsExactlyInAnyOrder(DeliveryChannel.TELEGRAM, DeliveryChannel.EMAIL);
        assertThat(result.fileName()).isEqualTo("Недельный отчет по ПЛ и маркетплейсам (28.09-04.10).xlsx");
        verify(telegramSender).sendDocument(eq(REPORT_CHAT_ID), anyString(), eq(WORKBOOK), anyString());
        verify(emailSender).sendReport(eq(RECIPIENTS), anyString(), anyString(), anyString(), eq(WORKBOOK));
        verify(deliveryRepository).markDelivered(WEEK.start(), DeliveryChannel.TELEGRAM);
        verify(deliveryRepository).markDelivered(WEEK.start(), DeliveryChannel.EMAIL);
    }

    @Test
    void emailIsNotPendingWhenNoRecipients() throws Exception {
        assertThat(service(List.of()).pendingChannels(WEEK)).containsExactly(DeliveryChannel.TELEGRAM);

        service(List.of()).deliver(WEEK);

        verifyNoInteractions(emailSender);
    }

    @Test
    void doesNotResendAlreadyDeliveredChannel() throws Exception {
        when(deliveryRepository.findDeliveredChannels(WEEK.start())).thenReturn(EnumSet.of(DeliveryChannel.TELEGRAM));

        WeeklyReportService.DeliveryResult result = service(RECIPIENTS).deliver(WEEK);

        assertThat(result.delivered()).containsExactly(DeliveryChannel.EMAIL);
        verify(telegramSender, never()).sendDocument(anyString(), anyString(), any(), anyString());
        verify(emailSender).sendReport(eq(RECIPIENTS), anyString(), anyString(), anyString(), eq(WORKBOOK));
    }

    @Test
    void fullyDeliveredWeekDoesNotEvenBuildTheReport() {
        when(deliveryRepository.findDeliveredChannels(WEEK.start()))
                .thenReturn(EnumSet.of(DeliveryChannel.TELEGRAM, DeliveryChannel.EMAIL));

        WeeklyReportService.DeliveryResult result = service(RECIPIENTS).deliver(WEEK);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.delivered()).isEmpty();
        assertThat(result.fileName()).isNull();
        verifyNoInteractions(generator, dailyMetricRepository, telegramSender, emailSender);
    }

    @Test
    void telegramFailureStillSendsEmailAndReportsFailure() throws Exception {
        doThrow(new TelegramApiException("api.telegram.org: Name or service not known"))
                .when(telegramSender).sendDocument(anyString(), anyString(), any(), anyString());

        WeeklyReportService.DeliveryResult result = service(RECIPIENTS).deliver(WEEK);

        assertThat(result.isComplete()).isFalse();
        assertThat(result.failures()).containsOnlyKeys(DeliveryChannel.TELEGRAM);
        assertThat(result.delivered()).containsExactly(DeliveryChannel.EMAIL);
        verify(deliveryRepository, never()).markDelivered(WEEK.start(), DeliveryChannel.TELEGRAM);
        verify(deliveryRepository).markDelivered(WEEK.start(), DeliveryChannel.EMAIL);
    }

    @Test
    void failureToRecordDeliveryStillCountsChannelAsDelivered() {
        doThrow(new RuntimeException("db down")).when(deliveryRepository).markDelivered(any(), any());

        WeeklyReportService.DeliveryResult result = service(List.of()).deliver(WEEK);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.delivered()).containsExactly(DeliveryChannel.TELEGRAM);
    }

    @Test
    void databaseFailureWhileBuildingPropagates() {
        when(dailyMetricRepository.findDailyTotals(any(), any())).thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service(RECIPIENTS).deliver(WEEK)).hasMessageContaining("Connection refused");
        verifyNoInteractions(telegramSender, emailSender);
    }

    @Test
    void pendingChannelsExcludesDelivered() {
        when(deliveryRepository.findDeliveredChannels(WEEK.start())).thenReturn(EnumSet.of(DeliveryChannel.EMAIL));

        Set<DeliveryChannel> pending = service(RECIPIENTS).pendingChannels(WEEK);

        assertThat(pending).containsExactly(DeliveryChannel.TELEGRAM);
    }
}
