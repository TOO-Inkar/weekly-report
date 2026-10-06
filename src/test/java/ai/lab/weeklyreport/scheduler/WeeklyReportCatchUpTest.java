package ai.lab.weeklyreport.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import ai.lab.weeklyreport.config.WeeklyReportDeliveryProperties;
import ai.lab.weeklyreport.config.WeeklyReportProperties;
import ai.lab.weeklyreport.excel.WeekRange;
import ai.lab.weeklyreport.repository.WeeklyReportDeliveryRepository;
import ai.lab.weeklyreport.service.DeliveryChannel;
import ai.lab.weeklyreport.service.WeeklyReportDeliveryRunner;
import ai.lab.weeklyreport.service.WeeklyReportService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeeklyReportCatchUpTest {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final WeekRange LAST_WEEK = new WeekRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));

    private final WeeklyReportDeliveryRepository deliveryRepository = mock(WeeklyReportDeliveryRepository.class);
    private final WeeklyReportService service = mock(WeeklyReportService.class);
    private final WeeklyReportDeliveryRunner runner = mock(WeeklyReportDeliveryRunner.class);

    private WeeklyReportCatchUp catchUpAt(String localDateTime) {
        Instant now = ZonedDateTime.parse(localDateTime + "+05:00[Asia/Almaty]").toInstant();
        return new WeeklyReportCatchUp(
                new WeeklyReportProperties("0 0 9 * * MON", "Asia/Almaty", null, null),
                new WeeklyReportDeliveryProperties(null, null, null, true),
                deliveryRepository, service, runner, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void hostDownAtScheduledTimeTriggersCatchUpForThatWeek() {
        // Хост перезагрузился в 08:55 понедельника и поднялся в 09:30 - запуск в 09:00 пропущен.
        when(deliveryRepository.hasAnyDelivery()).thenReturn(true);
        when(service.pendingChannels(LAST_WEEK)).thenReturn(EnumSet.of(DeliveryChannel.TELEGRAM, DeliveryChannel.EMAIL));

        assertThat(catchUpAt("2026-10-05T09:30:00").catchUpIfMissed()).isTrue();

        verify(runner).deliverWithRetries(eq(LAST_WEEK), anyString());
    }

    @Test
    void alreadyDeliveredWeekIsNotResent() {
        when(deliveryRepository.hasAnyDelivery()).thenReturn(true);
        when(service.pendingChannels(LAST_WEEK)).thenReturn(EnumSet.noneOf(DeliveryChannel.class));

        assertThat(catchUpAt("2026-10-07T12:00:00").catchUpIfMissed()).isFalse();

        verifyNoInteractions(runner);
    }

    @Test
    void restartBeforeMondayRunChecksPreviousWeekNotTheUpcomingOne() {
        // Понедельник 08:59: последний плановый запуск был неделю назад (отчёт за 21-27.09).
        WeekRange weekBefore = LAST_WEEK.previousWeek();
        when(deliveryRepository.hasAnyDelivery()).thenReturn(true);
        when(service.pendingChannels(weekBefore)).thenReturn(EnumSet.noneOf(DeliveryChannel.class));

        assertThat(catchUpAt("2026-10-05T08:59:00").catchUpIfMissed()).isFalse();

        verify(service).pendingChannels(weekBefore);
        verifyNoInteractions(runner);
    }

    @Test
    void emptyDeliveryJournalSkipsCatchUp() {
        when(deliveryRepository.hasAnyDelivery()).thenReturn(false);

        assertThat(catchUpAt("2026-10-05T09:30:00").catchUpIfMissed()).isFalse();

        verifyNoInteractions(service, runner);
    }

    @Test
    void databaseErrorDuringCheckIsLoggedNotThrown() {
        when(deliveryRepository.hasAnyDelivery()).thenThrow(new RuntimeException("db down"));

        assertThat(catchUpAt("2026-10-05T09:30:00").catchUpIfMissed()).isFalse();

        verify(runner, never()).deliverWithRetries(any(), anyString());
    }

    @Test
    void lastScheduledRunFindsMostRecentFireAtOrBeforeNow() {
        CronExpression cron = CronExpression.parse("0 0 9 * * MON");

        assertThat(WeeklyReportCatchUp.lastScheduledRun(cron, ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ALMATY)))
                .contains(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ALMATY));
        assertThat(WeeklyReportCatchUp.lastScheduledRun(cron, ZonedDateTime.of(2026, 10, 11, 23, 0, 0, 0, ALMATY)))
                .contains(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ALMATY));
        // Срабатываний реже раза в неделю за 7 дней может не быть - тогда ничего не догоняем.
        assertThat(WeeklyReportCatchUp.lastScheduledRun(CronExpression.parse("0 0 9 1 1 *"),
                ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ALMATY))).isEmpty();
    }
}
