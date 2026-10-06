package ai.lab.weeklyreport.repository;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import ai.lab.weeklyreport.service.DeliveryChannel;

/**
 * Журнал доставки недельного отчёта: в какие каналы отчёт за неделю (по дате понедельника) уже
 * ушёл. Нужен, чтобы повторные попытки и догоняющий запуск после рестарта не слали один и тот же
 * отчёт дважды.
 */
@Repository
public class WeeklyReportDeliveryRepository {

    private final JdbcTemplate jdbcTemplate;

    public WeeklyReportDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<DeliveryChannel> findDeliveredChannels(LocalDate weekStart) {
        Set<DeliveryChannel> delivered = EnumSet.noneOf(DeliveryChannel.class);
        jdbcTemplate.query("SELECT channel FROM weekly_report_deliveries WHERE week_start = ?",
                rs -> {
                    delivered.add(DeliveryChannel.valueOf(rs.getString("channel")));
                },
                weekStart);
        return delivered;
    }

    public void markDelivered(LocalDate weekStart, DeliveryChannel channel) {
        jdbcTemplate.update(
                "INSERT INTO weekly_report_deliveries (week_start, channel) VALUES (?, ?) ON CONFLICT DO NOTHING",
                weekStart, channel.name());
    }

    /** Есть ли хоть одна запись - без истории догоняющий запуск не делается (см. WeeklyReportCatchUp). */
    public boolean hasAnyDelivery() {
        Boolean exists = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM weekly_report_deliveries)", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }
}
