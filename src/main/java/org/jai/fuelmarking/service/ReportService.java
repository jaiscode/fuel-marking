package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.ReportDao;
import org.jai.fuelmarking.model.Records.ReportTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

public class ReportService {
    public enum Kind {
        INVENTORY("Inventory"), MARKING_BY_FUEL("Marking summary - by fuel type"),
        MARKING_BY_OMC("Marking summary - by oil marketing company"), MARKING_DETAIL("Marking detail (per truck)"),
        INCIDENTS("Incidents (spillage / leakage)"), TRANSFERS("Transfers"),
        STOCK_COUNTS("Stock counts (warehouse reconciliation)");

        private final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ReportDao dao = new ReportDao();

    /** Dates are inclusive; inventory ignores them (it is a point-in-time view). */
    public ReportTable run(Kind kind, UUID siteId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Choose a valid date range");
        LocalDateTime f = from.atStartOfDay(), t = to.plusDays(1).atStartOfDay();
        return Tx.call(c -> switch (kind) {
            case INVENTORY -> dao.inventory(c, siteId);
            case MARKING_BY_FUEL -> dao.markingByFuel(c, siteId, f, t);
            case MARKING_BY_OMC -> dao.markingByOmc(c, siteId, f, t);
            case MARKING_DETAIL -> dao.markingDetail(c, siteId, f, t);
            case INCIDENTS -> dao.incidents(c, siteId, f, t);
            case TRANSFERS -> dao.transfers(c, siteId, f, t);
            case STOCK_COUNTS -> dao.stockCounts(c, siteId, f, t);
        });
    }

    public void exportCsv(ReportTable table, Path file) {
        String csv = java.util.stream.Stream.concat(java.util.stream.Stream.of(table.headers()), table.rows().stream())
                .map(r -> r.stream().map(ReportService::quote).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n"));
        try {
            // A UTF-8 byte-order mark makes Excel (especially on Windows) read accents and non-Latin names correctly;
            // without it Excel assumes the local code page and garbles them.
            byte[] body = (csv + "\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[3 + body.length];
            bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;
            System.arraycopy(body, 0, bytes, 3, body.length);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    private static String quote(String s) {
        // Neutralise spreadsheet formula injection from free text, then CSV-quote.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?[0-9.]+")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
