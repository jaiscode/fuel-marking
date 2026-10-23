package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import org.jai.fuelmarking.model.Records.Container;
import org.jai.fuelmarking.service.ContainerService;
import org.jai.fuelmarking.session.Session;

class InventoryTab extends RefreshableTab {
    private final ContainerService service = new ContainerService();
    private final TableView<Container> table = new TableView<>();
    private final Label bulkKg = new Label(), jerryKg = new Label(), containerCount = new Label();
    private java.util.Map<String, String> colors = java.util.Map.of();

    InventoryTab() {
        super("Inventory");
        table.getColumns().add(Ui.col("Barcode", Container::barcode));
        table.getColumns().add(Ui.col("Type", k -> k.type().name()));
        var status = Ui.col("Status", Container::status);
        Ui.pillColumn(status);
        table.getColumns().add(status);
        table.getColumns().add(Ui.col("Seals", InventoryTab::seals));
        var rum = Ui.col("Rum type", (Container k) -> k.rumTypeCode() == null ? "" : k.rumTypeCode());
        RumSwatch.column(rum, k -> k.rumTypeCode(), () -> colors);
        table.getColumns().add(rum);
        table.getColumns().add(Ui.col("Volume (kg)", k -> Ui.kg(k.currentVolumeMl())));
        table.getColumns().add(Ui.col("Capacity (kg)", k -> Ui.kg(k.capacityMl())));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        javafx.scene.layout.HBox kpis = new javafx.scene.layout.HBox(14,
                Ui.kpi("IN IBCS / DRUMS", bulkKg, "Bulk rum held at this site"),
                Ui.kpi("IN JERRICANS", jerryKg, "Rum in jerricans, sealed or opened"),
                Ui.kpi("CONTAINERS", containerCount, "Items on the list below"));
        javafx.scene.layout.Region grow = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(grow, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox head = new javafx.scene.layout.HBox(12, Ui.header("Inventory", "Everything held at this site, as recorded in the system."), grow, refresh);
        head.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        setContent(Ui.page(head, kpis, table));
    }

    /** The seal number(s) on a container, or a dash when it carries none. */
    static String seals(Container k) {
        String s = java.util.stream.Stream.of(k.topSeal(), k.bottomSeal()).filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining(" / "));
        return s.isEmpty() ? "-" : s;
    }

    @Override
    void refresh() {
        colors = org.jai.fuelmarking.config.Tx.call(c -> new org.jai.fuelmarking.dao.MasterDataDao().rumColors(c));
        var items = service.inventory(Session.get().site().id());
        table.getItems().setAll(items);
        double bulk = items.stream().filter(k -> k.type().isBulk()).mapToDouble(Container::currentVolumeMl).sum();
        double jerry = items.stream().filter(k -> !k.type().isBulk()).mapToDouble(Container::currentVolumeMl).sum();
        bulkKg.setText(Ui.kg(bulk) + " kg");
        jerryKg.setText(Ui.kg(jerry) + " kg");
        containerCount.setText(String.valueOf(items.size()));
    }
}
