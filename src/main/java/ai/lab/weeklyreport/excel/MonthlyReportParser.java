package ai.lab.weeklyreport.excel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import ai.lab.weeklyreport.metric.MetricCatalog;
import ai.lab.weeklyreport.metric.MetricRow;

/**
 * Разбирает monthly_report.xlsx: листы = месяцы на русском, строка с ячейкой "Код аптеки"
 * - заголовок, дальше построчно (аптека, филиал, метрика) со значениями по дням в столбцах.
 * Файл содержит всю накопленную историю целиком (не дельту), поэтому парсер просто
 * отдаёт плоский поток строк - upsert-логика находится в слое репозитория.
 *
 * <p>Разбор потоковый (SAX через {@link XSSFReader}), а не через usermodel ({@code XSSFWorkbook}),
 * и принимает {@link Path}, а не {@code InputStream}, - это принципиально для памяти. Файл за месяц
 * содержит ~1.7 млн значений; {@code new XSSFWorkbook(InputStream)} вычитывал бы в heap и весь zip
 * целиком, и полную DOM-модель всех ячеек, а накопление результата списком добавляло бы сверху ещё
 * ~1.7 млн объектов {@link MetricRow}. Пик доходил до гигабайта, и контейнер убивал OOM-killer ядра.
 * Здесь {@code OPCPackage.open(File)} читает записи zip лениво, ячейки обрабатываются по одной, а
 * строки отдаются в {@code rowConsumer} по мере разбора - вызывающий сбрасывает их в БД чанками и
 * ничего не копит.
 */
@Component
public class MonthlyReportParser {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportParser.class);

    private static final Map<String, Integer> RUSSIAN_MONTHS = Map.ofEntries(
            Map.entry("январь", 1), Map.entry("февраль", 2), Map.entry("март", 3),
            Map.entry("апрель", 4), Map.entry("май", 5), Map.entry("июнь", 6),
            Map.entry("июль", 7), Map.entry("август", 8), Map.entry("сентябрь", 9),
            Map.entry("октябрь", 10), Map.entry("ноябрь", 11), Map.entry("декабрь", 12)
    );

    private static final String COL_PHARMACY_CODE = "код аптеки";
    private static final String COL_BRANCH_CODE = "код филиала";
    private static final String COL_METRIC = "метрика";
    private static final String COL_TOTAL = "итого";
    private static final String COL_HAS_DATA = "есть данные";

    /** Заголовок ищется только в первых 10 строках листа (перед ним бывает служебная шапка). */
    private static final int HEADER_SEARCH_LIMIT = 10;

    private final Clock clock;

    public MonthlyReportParser(Clock clock) {
        this.clock = clock;
    }

    /**
     * Потоково разбирает файл и отдаёт каждое найденное значение в {@code rowConsumer}.
     * Consumer вызывается из того же потока, что и {@code parse}, поэтому он может быть
     * не потокобезопасным - но он не должен копить строки, иначе смысл потокового разбора теряется.
     */
    public void parse(Path xlsxFile, Consumer<MetricRow> rowConsumer) {
        try (OPCPackage pkg = OPCPackage.open(xlsxFile.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg, true);
            SharedStrings sharedStrings = reader.getSharedStringsTable();
            XSSFReader.SheetIterator sheets = reader.getSheetIterator();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    parseSheet(sheetStream, sheets.getSheetName(), sharedStrings, rowConsumer);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать monthly_report.xlsx", e);
        } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Не удалось разобрать monthly_report.xlsx", e);
        }
    }

    private void parseSheet(InputStream sheetStream, String sheetName, SharedStrings sharedStrings,
                            Consumer<MetricRow> rowConsumer)
            throws IOException, SAXException, ParserConfigurationException {
        Integer month = RUSSIAN_MONTHS.get(sheetName.trim().toLowerCase(Locale.ROOT));
        if (month == null) {
            log.debug("Пропускаю лист '{}': не распознан как месяц", sheetName);
            return;
        }
        SheetHandler handler = new SheetHandler(sheetName, resolveYear(month), month, sharedStrings, rowConsumer);
        XMLReader xmlReader = newXmlReader();
        xmlReader.setContentHandler(handler);
        try {
            xmlReader.parse(new InputSource(sheetStream));
        } catch (EndOfSheetData e) {
            // Блок данных закончился - остаток листа читать не нужно.
        }
        if (!handler.headerFound()) {
            log.warn("Пропускаю лист '{}': не найдена строка заголовка (нет ячейки '{}')", sheetName, COL_PHARMACY_CODE);
        }
    }

    /**
     * В файле нет явного года, поэтому он определяется от текущей даты: месяцы позже
     * текущего считаются относящимися к предыдущему году (например, лист "Декабрь",
     * встреченный в январе, - это декабрь предыдущего года).
     */
    private int resolveYear(int month) {
        LocalDate now = LocalDate.now(clock);
        return month <= now.getMonthValue() ? now.getYear() : now.getYear() - 1;
    }

    private static XMLReader newXmlReader() throws SAXException, ParserConfigurationException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newSAXParser().getXMLReader();
    }

    /**
     * SAX-обработчик одного листа: собирает текущую строку XML в {@link RawCell}-ячейки и на
     * закрытии {@code <row>} применяет к ней ту же логику, что раньше работала поверх
     * usermodel-{@code Row} (поиск заголовка, остановка на конце блока данных, разбор метрики).
     */
    private static final class SheetHandler extends DefaultHandler {

        private final String sheetName;
        private final int year;
        private final int month;
        private final int daysInMonth;
        private final SharedStrings sharedStrings;
        private final Consumer<MetricRow> rowConsumer;

        private HeaderColumns columns;
        /** Номер строки (0-based), которая должна идти следующей; разрыв нумерации = конец данных. */
        private int nextDataRowNum = -1;

        private int currentRowNum = -1;
        private final Map<Integer, RawCell> currentRow = new HashMap<>();
        private int lastColumn = -1;
        private String cellType;
        private int cellColumn = -1;
        private final StringBuilder cellText = new StringBuilder();
        private boolean capturing;

        private SheetHandler(String sheetName, int year, int month, SharedStrings sharedStrings,
                             Consumer<MetricRow> rowConsumer) {
            this.sheetName = sheetName;
            this.year = year;
            this.month = month;
            this.daysInMonth = YearMonth.of(year, month).lengthOfMonth();
            this.sharedStrings = sharedStrings;
            this.rowConsumer = rowConsumer;
        }

        boolean headerFound() {
            return columns != null;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (qName) {
                case "row" -> {
                    currentRowNum = rowIndex(attributes.getValue("r"), currentRowNum);
                    currentRow.clear();
                    lastColumn = -1;
                }
                case "c" -> {
                    cellColumn = columnIndex(attributes.getValue("r"), lastColumn);
                    lastColumn = cellColumn;
                    cellType = attributes.getValue("t");
                    cellText.setLength(0);
                    capturing = false;
                }
                // <v> - значение ячейки, <t> - текст inline-строки (<is><t>, в rich text их несколько).
                case "v" -> {
                    cellText.setLength(0);
                    capturing = true;
                }
                case "t" -> capturing = true;
                default -> {
                    // Остальные элементы (<f>, <is>, <sheetData>, ...) сами по себе значений не несут.
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturing) {
                cellText.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "v", "t" -> capturing = false;
                case "c" -> currentRow.put(cellColumn, toRawCell());
                case "row" -> processRow();
                default -> {
                    // см. startElement
                }
            }
        }

        private RawCell toRawCell() {
            String raw = cellText.toString();
            if (cellType == null) {
                return new RawCell(CellKind.NUMBER, raw);
            }
            return switch (cellType) {
                case "n" -> new RawCell(CellKind.NUMBER, raw);
                case "s" -> new RawCell(CellKind.STRING, sharedString(raw));
                case "inlineStr", "str" -> new RawCell(CellKind.STRING, raw);
                // "b" (boolean) и "e" (ошибка) в прежнем парсере тоже давали ""/0.
                default -> new RawCell(CellKind.OTHER, "");
            };
        }

        private String sharedString(String index) {
            try {
                return sharedStrings.getItemAt(Integer.parseInt(index.trim())).getString();
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                log.warn("Лист '{}', строка {}: битая ссылка на таблицу строк ('{}'), ячейка считана как пустая",
                        sheetName, currentRowNum + 1, index);
                return "";
            }
        }

        private void processRow() {
            if (columns == null) {
                if (currentRowNum >= HEADER_SEARCH_LIMIT) {
                    throw new EndOfSheetData();
                }
                if (isHeaderRow()) {
                    columns = HeaderColumns.from(currentRow);
                    nextDataRowNum = currentRowNum + 1;
                }
                return;
            }
            // Пропуск в нумерации строк = строки в файле нет вообще, значит блок данных закончился.
            if (currentRowNum != nextDataRowNum) {
                throw new EndOfSheetData();
            }
            nextDataRowNum++;

            String pharmacyCode = stringValue(currentRow.get(columns.pharmacyCodeCol()));
            if (pharmacyCode.isEmpty()) {
                throw new EndOfSheetData();
            }
            String metricLabel = stringValue(currentRow.get(columns.metricCol()));
            if (metricLabel.isEmpty()) {
                return;
            }
            int metricNum;
            try {
                metricNum = MetricCatalog.parseMetricNum(metricLabel);
            } catch (IllegalArgumentException e) {
                log.warn("Лист '{}', строка {}: не удалось разобрать метрику '{}', строка пропущена",
                        sheetName, currentRowNum + 1, metricLabel);
                return;
            }
            String branchCode = stringValue(currentRow.get(columns.branchCodeCol()));

            for (Map.Entry<Integer, Integer> dayColumn : columns.dayColumns().entrySet()) {
                int day = dayColumn.getKey();
                if (day < 1 || day > daysInMonth) {
                    continue;
                }
                BigDecimal value = numericValue(currentRow.get(dayColumn.getValue()));
                rowConsumer.accept(new MetricRow(pharmacyCode, branchCode, metricNum, LocalDate.of(year, month, day), value));
            }
        }

        private boolean isHeaderRow() {
            for (RawCell cell : currentRow.values()) {
                if (COL_PHARMACY_CODE.equals(stringValue(cell).toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        /** Номер строки из атрибута {@code r} (1-based); если его нет - строка идёт следом за предыдущей. */
        private static int rowIndex(String ref, int previousRowNum) {
            if (ref == null) {
                return previousRowNum + 1;
            }
            try {
                return Integer.parseInt(ref.trim()) - 1;
            } catch (NumberFormatException e) {
                return previousRowNum + 1;
            }
        }

        /** Индекс столбца из ссылки вида {@code AB12}; если её нет - ячейка идёт следом за предыдущей. */
        private static int columnIndex(String cellRef, int previousColumn) {
            if (cellRef == null) {
                return previousColumn + 1;
            }
            int column = 0;
            for (int i = 0; i < cellRef.length(); i++) {
                char c = Character.toUpperCase(cellRef.charAt(i));
                if (c < 'A' || c > 'Z') {
                    break;
                }
                column = column * 26 + (c - 'A' + 1);
            }
            return column > 0 ? column - 1 : previousColumn + 1;
        }
    }

    /** Управляющее исключение: прекращает SAX-разбор листа, когда дальше данных нет. */
    private static final class EndOfSheetData extends RuntimeException {

        private EndOfSheetData() {
            super(null, null, false, false);
        }
    }

    private enum CellKind { STRING, NUMBER, OTHER }

    /** Ячейка в том виде, в каком она лежит в XML листа: вид значения + ещё не разобранный текст. */
    private record RawCell(CellKind kind, String text) {
    }

    private static BigDecimal numericValue(RawCell cell) {
        if (cell == null) {
            return BigDecimal.ZERO;
        }
        return switch (cell.kind()) {
            case NUMBER -> parseNumber(cell.text());
            case STRING -> parseNumericString(cell.text());
            case OTHER -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal parseNumber(String raw) {
        if (raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal parseNumericString(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(trimmed.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String stringValue(RawCell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.kind()) {
            case STRING -> cell.text().trim();
            case NUMBER -> numberAsString(cell.text());
            case OTHER -> "";
        };
    }

    /** Числовые коды аптек в файле встречаются и числом, и строкой - целые приводим без ".0". */
    private static String numberAsString(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            double d = Double.parseDouble(trimmed);
            return d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d)
                    : String.valueOf(d);
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    private record HeaderColumns(int pharmacyCodeCol, int branchCodeCol, int metricCol, Map<Integer, Integer> dayColumns) {

        static HeaderColumns from(Map<Integer, RawCell> headerRow) {
            int pharmacyCodeCol = -1;
            int branchCodeCol = -1;
            int metricCol = -1;
            Map<Integer, Integer> dayColumns = new LinkedHashMap<>();

            List<Integer> orderedColumns = headerRow.keySet().stream().sorted().toList();
            for (int column : orderedColumns) {
                String normalized = stringValue(headerRow.get(column)).toLowerCase(Locale.ROOT);
                switch (normalized) {
                    case COL_PHARMACY_CODE -> pharmacyCodeCol = column;
                    case COL_BRANCH_CODE -> branchCodeCol = column;
                    case COL_METRIC -> metricCol = column;
                    case COL_TOTAL, COL_HAS_DATA -> {
                        // производные столбцы - не относятся к конкретному дню, пропускаем
                    }
                    default -> {
                        try {
                            dayColumns.put(Integer.parseInt(normalized), column);
                        } catch (NumberFormatException ignored) {
                            // нераспознанный заголовок вне списка ожидаемых - игнорируем
                        }
                    }
                }
            }

            if (pharmacyCodeCol < 0 || branchCodeCol < 0 || metricCol < 0) {
                throw new IllegalStateException(
                        "В заголовке листа не найдены обязательные столбцы (Код аптеки/Код филиала/Метрика)");
            }
            return new HeaderColumns(pharmacyCodeCol, branchCodeCol, metricCol, dayColumns);
        }
    }
}
