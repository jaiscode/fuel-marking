package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.ContainerDao;
import org.jai.fuelmarking.dao.SignatureDao;
import org.jai.fuelmarking.dao.StockCountDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Stock reconciliation at the warehouse: the manager counts the rum in every IBC/drum held at the site (weighed on the scale or typed)
 * and signs. The count and its differences are recorded and sent to head office; the system's stock is NOT changed by it.
 */
public class StockCountService {
    private final ContainerDao containers = new ContainerDao();
    private final StockCountDao counts = new StockCountDao();
    private final SignatureDao signatures = new SignatureDao();

    /** The bulk containers that are at the site now (ones on the road are somebody else's to count). */
    public List<Container> toCount(UUID siteId) {
        return Tx.call(c -> containers.listBySite(c, siteId).stream()
                .filter(k -> k.type().isBulk() && !"IN_TRANSIT".equals(k.status())).toList());
    }

    /** Every bulk container at the site must be counted, once. Returns the signed count. */
    public StockCount submit(AppUser manager, Site site, List<StockEntry> entries, String comment, byte[] signature) {
        Guard.require(manager, Role.WAREHOUSE_MANAGER);
        Guard.signed(signature);
        if (comment != null && comment.length() > 500) throw new IllegalArgumentException("The comment is too long (500 characters at most)");
        return Tx.call(c -> {
            List<Container> held = containers.listBySite(c, site.id()).stream()
                    .filter(k -> k.type().isBulk() && !"IN_TRANSIT".equals(k.status())).toList();
            if (held.isEmpty()) throw new IllegalStateException("There is no IBC or drum at " + site.name() + " to count");
            Map<String, StockEntry> byBarcode = new HashMap<>();
            for (StockEntry e : entries) {
                if (byBarcode.put(e.barcode(), e) != null) throw new IllegalArgumentException(e.barcode() + " was counted twice");
                if (Double.isNaN(e.countedG()) || e.countedG() < 0) throw new IllegalArgumentException("The count for " + e.barcode() + " cannot be negative");
                if (!"SCALE".equals(e.source()) && !"MANUAL".equals(e.source())) throw new IllegalArgumentException("Count source must be SCALE or MANUAL");
            }
            List<String> missing = held.stream().map(Container::barcode).filter(b -> !byBarcode.containsKey(b)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("Count every IBC and drum - missing: " + String.join(", ", missing));
            Set<String> heldCodes = new HashSet<>();
            held.forEach(k -> heldCodes.add(k.barcode()));
            for (String b : byBarcode.keySet()) if (!heldCodes.contains(b)) throw new IllegalArgumentException(b + " is not an IBC or drum held at " + site.name());

            Map<UUID, StockCountLine> lines = new LinkedHashMap<>();
            double system = 0, counted = 0;
            for (Container k : held) {
                StockEntry e = byBarcode.get(k.barcode());
                lines.put(k.id(), new StockCountLine(k.barcode(), k.type().code(), k.rumTypeCode(), k.currentVolumeMl(), e.countedG(), e.source()));
                system += k.currentVolumeMl();
                counted += e.countedG();
            }
            StockCount sc = new StockCount(UUID.randomUUID(), site.id(), manager.id(), LocalDateTime.now().withNano(0),
                    comment == null || comment.isBlank() ? null : comment.trim(), system, counted);
            counts.insert(c, sc, lines);
            signatures.save(c, "STOCK_COUNT", sc.id(), "STOCK_COUNT", manager.id(), signature);
            counts.publish(c, sc);
            return sc;
        });
    }

    public List<StockCount> history(UUID siteId) { return Tx.call(c -> counts.history(c, siteId, "WAREHOUSE", 50)); }

    public List<StockCountLine> lines(UUID stockCountId) { return Tx.call(c -> counts.lines(c, stockCountId)); }
}
