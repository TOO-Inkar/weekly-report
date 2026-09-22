package ai.lab.weeklyreport.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.lab.weeklyreport.excel.MonthlyReportParser;
import ai.lab.weeklyreport.metric.MetricRow;
import ai.lab.weeklyreport.repository.DailyMetricRepository;
import ai.lab.weeklyreport.repository.IngestedFileRepository;

/**
 * Оркестрирует приём monthly_report.xlsx: проверка идемпотентности по telegram_file_id,
 * потоковый разбор файла с записью в daily_metrics чанками и запись строки аудита в ingested_files.
 * Всё это - одна транзакция: либо файл записан целиком и появилась строка аудита, либо ничего.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /**
     * Сколько строк держать в памяти между сбросами в БД. Ограничивает пик памяти на ингестии:
     * файл за месяц даёт ~1.7 млн строк, и копить их целиком нельзя (см. {@link MonthlyReportParser}).
     */
    private static final int FLUSH_SIZE = 10_000;

    /** Шаг прогресс-логов: запись файла занимает минуты, без них не видно, живой ли процесс. */
    private static final int PROGRESS_STEP = 250_000;

    private final MonthlyReportParser parser;
    private final DailyMetricRepository dailyMetricRepository;
    private final IngestedFileRepository ingestedFileRepository;

    public IngestionService(MonthlyReportParser parser,
                             DailyMetricRepository dailyMetricRepository,
                             IngestedFileRepository ingestedFileRepository) {
        this.parser = parser;
        this.dailyMetricRepository = dailyMetricRepository;
        this.ingestedFileRepository = ingestedFileRepository;
    }

    @Transactional
    public void ingest(String telegramFileId, String fileName, Path xlsxFile) {
        if (ingestedFileRepository.existsByTelegramFileId(telegramFileId)) {
            log.info("Файл '{}' (telegram_file_id={}) уже был обработан ранее, пропускаю", fileName, telegramFileId);
            return;
        }

        log.info("Файл '{}': начинаю разбор с записью в БД", fileName);
        ChunkedWriter writer = new ChunkedWriter(fileName);
        parser.parse(xlsxFile, writer);
        writer.flush();

        int rowCount = writer.written();
        ingestedFileRepository.recordIngestedFile(telegramFileId, fileName, rowCount);
        log.info("Файл '{}' записан в БД: {} строк", fileName, rowCount);
    }

    /**
     * Принимает строки от потокового парсера и сбрасывает их в БД по {@link #FLUSH_SIZE} штук.
     * Вызывается из потока разбора, синхронизация не нужна.
     */
    private final class ChunkedWriter implements Consumer<MetricRow> {

        private final String fileName;
        private final List<MetricRow> buffer = new ArrayList<>(FLUSH_SIZE);
        private int written;
        private int nextProgressAt = PROGRESS_STEP;

        private ChunkedWriter(String fileName) {
            this.fileName = fileName;
        }

        @Override
        public void accept(MetricRow row) {
            buffer.add(row);
            if (buffer.size() >= FLUSH_SIZE) {
                flush();
            }
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            dailyMetricRepository.upsertAll(buffer);
            written += buffer.size();
            buffer.clear();
            if (written >= nextProgressAt) {
                log.info("Файл '{}': записано {} строк", fileName, written);
                nextProgressAt = written + PROGRESS_STEP;
            }
        }

        int written() {
            return written;
        }
    }
}
