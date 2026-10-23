package org.jai.fuelmarking.central.web;

import org.jai.fuelmarking.central.web.ReadModels.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Read API for the web app. Dates are inclusive (yyyy-MM-dd); lists are newest first. */
@RestController
@RequestMapping("/api/web")
public class ReadController {
    private final ReadService service;

    public ReadController(ReadService service) { this.service = service; }

    private static final String ISO = "yyyy-MM-dd";

    @GetMapping("/overview")
    Overview overview(@RequestParam(required = false) UUID siteId,
                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to) {
        return service.overview(siteId, from, to);
    }

    @GetMapping("/lookups")
    Lookups lookups() { return service.lookups(); }

    @GetMapping("/sites/status")
    List<SiteStatus> siteStatus() { return service.siteStatus(); }

    @GetMapping("/markings")
    Page<MarkingRow> markings(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) UUID omcId,
                              @RequestParam(required = false) String fuel, @RequestParam(required = false) String status,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.markings(siteId, omcId, fuel, status, q, from, to, page, size);
    }

    @GetMapping("/markings/summary")
    List<SummaryRow> markingSummary(@RequestParam(defaultValue = "fuel") String groupBy, @RequestParam(required = false) UUID siteId,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to) {
        return service.markingSummary(groupBy, siteId, from, to);
    }

    @GetMapping("/markings/{id}")
    MarkingDetail marking(@PathVariable UUID id) {
        return service.marking(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Marking not found"));
    }

    @GetMapping("/containers")
    Page<ContainerRow> containers(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String type, @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.containers(siteId, status, type, q, page, size);
    }

    @GetMapping("/inventory")
    List<InventoryRow> inventory(@RequestParam(required = false) UUID siteId) { return service.inventory(siteId); }

    @GetMapping("/incidents")
    Page<IncidentRow> incidents(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) String type, @RequestParam(required = false) String rumType,
                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.incidents(siteId, type, rumType, from, to, page, size);
    }

    @GetMapping("/shifts")
    Page<ShiftRow> shifts(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) String status,
                          @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.shifts(siteId, status, from, to, page, size);
    }

    @GetMapping("/stock-counts")
    Page<StockCountRow> stockCounts(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.stockCounts(siteId, kind, from, to, page, size);
    }

    @GetMapping("/stock-counts/{id}")
    StockCountDetail stockCount(@PathVariable UUID id) {
        return service.stockCount(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock count not found"));
    }

    @GetMapping("/transfers")
    Page<TransferRow> transfers(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) String status,
                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.transfers(siteId, status, from, to, page, size);
    }

    @GetMapping("/transfers/{id}")
    TransferDetail transfer(@PathVariable UUID id) {
        return service.transfer(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
    }

    @GetMapping(value = "/signatures/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> signature(@PathVariable UUID id) {
        byte[] png = service.signatureImage(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Signature not found"));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate()).body(png);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
