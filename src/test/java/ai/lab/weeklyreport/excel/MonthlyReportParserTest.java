package ai.lab.weeklyreport.excel;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.lab.weeklyreport.metric.MetricRow;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyReportParserTest {

    // "Сейчас" зафиксировано в августе 2026, поэтому лист "Январь" должен разрешиться в январь 2026 года.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneId.of("Asia/Almaty"));

    private final MonthlyReportParser parser = new MonthlyReportParser(FIXED_CLOCK);

    @Test
    void parsesFixtureIntoExpectedRows() throws URISyntaxException {
        List<MetricRow> rows = new ArrayList<>();
        parser.parse(fixture(), rows::add);

        // Январь: 2 строки данных x 3 дневных столбца + 2 строки данных x 3 столбца = 12.
        // Февраль (только заголовок) и "Сводка" (не месяц) строк не дают.
        assertThat(rows).hasSize(12);

        assertThat(rows).noneMatch(r -> r.pharmacyCode().equals("9999"));

        List<MetricRow> newClients = rows.stream()
                .filter(r -> r.pharmacyCode().equals("1001") && r.metricNum() == 1)
                .sorted((a, b) -> a.metricDate().compareTo(b.metricDate()))
                .toList();
        assertThat(newClients).hasSize(3);
        assertThat(newClients.get(0).branchCode()).isEqualTo("F1");
        assertThat(newClients.get(0).metricDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertValue(newClients.get(0).value(), 5);
        assertValue(newClients.get(1).value(), 3);
        assertValue(newClients.get(2).value(), 0);

        List<MetricRow> accrualCount = rows.stream()
                .filter(r -> r.pharmacyCode().equals("1001") && r.metricNum() == 2)
                .sorted((a, b) -> a.metricDate().compareTo(b.metricDate()))
                .toList();
        assertThat(accrualCount).hasSize(3);
        assertValue(accrualCount.get(0).value(), 10);
        assertValue(accrualCount.get(1).value(), 0);
        assertValue(accrualCount.get(2).value(), 2);

        List<MetricRow> daribarCount = rows.stream()
                .filter(r -> r.pharmacyCode().equals("1002") && r.metricNum() == 11)
                .sorted((a, b) -> a.metricDate().compareTo(b.metricDate()))
                .toList();
        assertThat(daribarCount).hasSize(3);
        assertThat(daribarCount.get(0).branchCode()).isEqualTo("F2");
        assertValue(daribarCount.get(0).value(), 7);
        assertValue(daribarCount.get(1).value(), 8);
        assertValue(daribarCount.get(2).value(), 9);

        List<MetricRow> daribarSum = rows.stream()
                .filter(r -> r.pharmacyCode().equals("1002") && r.metricNum() == 12)
                .sorted((a, b) -> a.metricDate().compareTo(b.metricDate()))
                .toList();
        assertThat(daribarSum).hasSize(3);
        assertValue(daribarSum.get(0).value(), new BigDecimal("1000.5"));
        assertValue(daribarSum.get(1).value(), new BigDecimal("2000"));
        assertValue(daribarSum.get(2).value(), BigDecimal.ZERO);
    }

    /**
     * В потоковом разборе строки, которой нет в файле вообще, просто не приходит SAX-события -
     * конец блока данных приходится определять по разрыву в нумерации (в разборе через usermodel
     * это была проверка {@code sheet.getRow(i) == null}).
     */
    @Test
    void stopsAtGapInRowNumbering(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("gap.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Январь");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Код аптеки");
            header.createCell(1).setCellValue("Код филиала");
            header.createCell(2).setCellValue("Метрика");
            header.createCell(3).setCellValue(1);

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("1001");
            data.createCell(1).setCellValue("F1");
            data.createCell(2).setCellValue("1-Новых клиентов");
            data.createCell(3).setCellValue(5);

            // Строка 3 (индекс 2) намеренно не создаётся - в XML её не будет совсем, поэтому
            // "мусорная" строка 4 (индекс 3) не должна попасть в результат разбора.
            Row trailing = sheet.createRow(3);
            trailing.createCell(0).setCellValue("9999");
            trailing.createCell(1).setCellValue("F9");
            trailing.createCell(2).setCellValue("1-Новых клиентов");
            trailing.createCell(3).setCellValue(999);

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<MetricRow> rows = new ArrayList<>();
        parser.parse(file, rows::add);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().pharmacyCode()).isEqualTo("1001");
        assertValue(rows.getFirst().value(), 5);
    }

    private static Path fixture() throws URISyntaxException {
        URL resource = MonthlyReportParserTest.class.getResource("/fixtures/monthly_report_test.xlsx");
        return Path.of(Objects.requireNonNull(resource, "тестовая фикстура не найдена на classpath").toURI());
    }

    private static void assertValue(BigDecimal actual, long expected) {
        assertValue(actual, BigDecimal.valueOf(expected));
    }

    private static void assertValue(BigDecimal actual, BigDecimal expected) {
        assertThat(actual.compareTo(expected)).as("value %s should equal %s", actual, expected).isZero();
    }
}
