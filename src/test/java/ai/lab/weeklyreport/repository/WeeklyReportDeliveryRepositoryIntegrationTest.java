package ai.lab.weeklyreport.repository;

import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.lab.weeklyreport.service.DeliveryChannel;

import static org.assertj.core.api.Assertions.assertThat;

/** Журнал доставки недельного отчёта на реальном PostgreSQL: идемпотентность отметок и поиск по неделе. */
@Testcontainers
class WeeklyReportDeliveryRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 28);

    private WeeklyReportDeliveryRepository repository;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("TRUNCATE weekly_report_deliveries");
        repository = new WeeklyReportDeliveryRepository(jdbcTemplate);
    }

    @Test
    void emptyJournal() {
        assertThat(repository.hasAnyDelivery()).isFalse();
        assertThat(repository.findDeliveredChannels(WEEK)).isEmpty();
    }

    @Test
    void markDeliveredIsIdempotentAndScopedToWeek() {
        repository.markDelivered(WEEK, DeliveryChannel.TELEGRAM);
        repository.markDelivered(WEEK, DeliveryChannel.TELEGRAM);
        repository.markDelivered(WEEK.minusWeeks(1), DeliveryChannel.EMAIL);

        assertThat(repository.hasAnyDelivery()).isTrue();
        assertThat(repository.findDeliveredChannels(WEEK)).containsExactly(DeliveryChannel.TELEGRAM);
        assertThat(repository.findDeliveredChannels(WEEK.minusWeeks(1))).containsExactly(DeliveryChannel.EMAIL);
    }
}
