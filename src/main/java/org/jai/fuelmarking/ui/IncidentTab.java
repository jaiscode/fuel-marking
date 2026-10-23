package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import org.jai.fuelmarking.model.Records.Incident;
import org.jai.fuelmarking.service.IncidentService;
import org.jai.fuelmarking.session.Session;

import java.time.LocalDateTime;

class IncidentTab extends RefreshableTab {
    private final IncidentService service = new IncidentService();
    private final ComboBox<String> type = new ComboBox<>();
    private final TextField qty = new TextField();
    private final TextField container = new TextField();
    private final ComboBox<org.jai.fuelmarking.model.Records.RumType> rum = new ComboBox<>();
    private java.util.Map<String, String> rumColors = java.util.Map.of();
    private final org.jai.fuelmarking.service.ContainerService containerService = new org.jai.fuelmarking.service.ContainerService();
    private final TextArea description = new TextArea();
    private final TableView<Incident> table = new TableView<>();

    IncidentTab() {
        super("Incidents");
        type.getItems().setAll(IncidentService.TYPES);
        type.getSelectionModel().selectFirst();
        container.setPromptText("Container barcode (optional - reduces its volume)");
        rum.setPromptText("Which rum was lost?");
        RumSwatch.style(rum);
        rum.setMaxWidth(Double.MAX_VALUE);
        // a container named: the rum lost is the rum it holds; none named: the rum type has to be chosen
        Runnable fromContainer = () -> {
            var k = containerService.find(container.getText() == null ? "" : container.getText().trim());
            if (k.isPresent() && k.get().rumTypeCode() != null) {
                rum.getItems().stream().filter(t -> t.code().equals(k.get().rumTypeCode())).findFirst().ifPresent(rum.getSelectionModel()::select);
                rum.setDisable(true);
            } else rum.setDisable(false);
        };
        container.setOnAction(e -> fromContainer.run());
        container.focusedProperty().addListener((o, was, now) -> { if (!now) fromContainer.run(); });
        description.setPrefRowCount(3);
        Button report = Ui.primary(new Button("Report incident"));
        report.setOnAction(e -> Ui.attempt(this::report));
        table.getColumns().add(Ui.col("When", i -> i.occurredAt().toLocalDate() + " " + i.occurredAt().toLocalTime().withNano(0).withSecond(0)));
        table.getColumns().add(Ui.col("Type", Incident::type));
        table.getColumns().add(Ui.col("Quantity (g)", i -> Ui.fmt(i.quantityMl())));
        var rumColumn = Ui.col("Rum type", (Incident i) -> i.rumTypeCode() == null ? "" : i.rumTypeCode());
        RumSwatch.column(rumColumn, Incident::rumTypeCode, () -> rumColors);
        table.getColumns().add(rumColumn);
        table.getColumns().add(Ui.col("Description", i -> i.description() == null ? "" : i.description()));

        var f = Ui.form();
        Ui.row(f, 0, "Type", type);
        Ui.row(f, 1, "Quantity lost (g)", qty);
        Ui.row(f, 2, "Container", container);
        Ui.row(f, 3, "Rum type", rum);
        Ui.row(f, 4, "What happened", description);
        f.add(report, 1, 5);
        table.setPrefHeight(260);
        setContent(Ui.page(Ui.header("Incidents", "Record rum that was spilled or leaked, and which rum it was, so the shift reconciliation stays honest."), f,
                Ui.section("Last 7 days at this site"), table));
    }

    private void report() {
        double q;
        try { q = Double.parseDouble(qty.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Quantity must be a number"); }
        Session s = Session.get();
        service.report(s.user(), s.site(), s.shift(), container.getText().trim(), rum.getValue() == null ? null : rum.getValue().code(), type.getValue(), q, description.getText());
        qty.clear(); container.clear(); description.clear(); rum.getSelectionModel().clearSelection(); rum.setDisable(false);
        refresh();
    }

    @Override
    void refresh() {
        Session s = Session.get();
        var rums = org.jai.fuelmarking.config.Tx.<java.util.List<org.jai.fuelmarking.model.Records.RumType>>call(c -> new org.jai.fuelmarking.dao.MasterDataDao().rumTypes(c));
        rumColors = new java.util.HashMap<>();
        for (var r : rums) rumColors.put(r.code(), r.color());
        var chosen = rum.getValue();
        rum.getItems().setAll(rums);
        if (chosen != null) rum.getItems().stream().filter(t -> t.code().equals(chosen.code())).findFirst().ifPresent(rum.getSelectionModel()::select);
        table.getItems().setAll(service.list(s.site().id(), LocalDateTime.now().minusDays(7), LocalDateTime.now().plusMinutes(1)));
    }
}
