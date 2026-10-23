package org.jai.fuelmarking.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.StockCountService;
import org.jai.fuelmarking.session.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * Warehouse manager: reconcile the total stock. Every IBC and drum at the site is counted (weighed on the scale or typed), the
 * differences against the system are shown, and the count is signed. A count is a record: it does not change the system's stock.
 */
class StockCountTab extends RefreshableTab {
    /** One bulk container being counted. The counted amount is typed or read from the scale, in kg. */
    static final class Row {
        final Container container;
        final StringProperty counted = new SimpleStringProperty("");
        final StringProperty source = new SimpleStringProperty("");
        final javafx.beans.binding.StringBinding difference;
        Row(Container c) {
            this.container = c;
            this.difference = Bindings.createStringBinding(() -> {
                Double g = countedGrams();
                return g == null ? "" : signed(g - c.currentVolumeMl());
            }, counted);
        }
        /** The counted amount in grams, or null when nothing (or something unreadable) is entered. */
        Double countedGrams() {
            if (counted.get().isBlank()) return null;
            try { return Ui.gramsFromKg(counted.get(), "Count"); } catch (IllegalArgumentException e) { return null; }
        }
    }

    private final StockCountService service = new StockCountService();
    private final TableView<Row> table = new TableView<>();
    private final TableView<StockCount> history = new TableView<>();
    private final Label totals = new Label();
    private final TextField comment = new TextField();
    private java.util.Map<String, String> colors = java.util.Map.of();

    StockCountTab() {
        super("Stock count");
        Label intro = new Label("Count the rum in every IBC and drum at this warehouse. Put each one on the scale (or type the figure), "
                + "check the difference, then sign. The count is a record - it does not change the stock in the system.");
        intro.setWrapText(true);

        table.setEditable(true);
        table.getColumns().add(Ui.col("Container", (Row r) -> r.container.barcode()));
        table.getColumns().add(Ui.col("Type", (Row r) -> r.container.type().name()));
        var rum = Ui.col("Rum type", (Row r) -> r.container.rumTypeCode() == null ? "" : r.container.rumTypeCode());
        RumSwatch.column(rum, r -> r.container.rumTypeCode(), () -> colors);
        table.getColumns().add(rum);
        table.getColumns().add(Ui.col("System (kg)", (Row r) -> Ui.kg(r.container.currentVolumeMl())));
        TableColumn<Row, String> counted = new TableColumn<>("Counted (kg) - type it, or read the scale");
        counted.setCellValueFactory(cd -> cd.getValue().counted);
        counted.setCellFactory(c -> new CountCell());     // a text box in every row: what is typed counts at once, no Enter needed
        counted.setSortable(false);
        counted.setPrefWidth(260);
        table.getColumns().add(counted);
        TableColumn<Row, String> diff = new TableColumn<>("Difference (kg)");
        diff.setCellValueFactory(cd -> cd.getValue().difference);
        diff.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                setStyle(empty || v == null || v.isEmpty() || isZero(v) ? "" : "-fx-text-fill: #b3261e; -fx-font-weight: bold;");
            }
        });
        diff.setPrefWidth(150);
        table.getColumns().add(diff);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("There is no IBC or drum at this site to count"));
        table.setPrefHeight(260);

        Button read = new Button("Read scale for selected");
        read.setOnAction(e -> {
            Row row = table.getSelectionModel().getSelectedItem();
            if (row == null) { Ui.error("Select the container on the scale first."); return; }
            Weigh.readStable(read, kg -> {
                row.counted.set(Ui.kg(Weigh.roundGrams(kg)));
                row.source.set("SCALE");
                updateTotals();
            });
        });
        Button tare = new Button("Tare scale");
        tare.setOnAction(e -> Weigh.tare(tare));
        Button reload = new Button("Start again");
        reload.setOnAction(e -> refresh());
        comment.setPromptText("Comment (optional)");
        comment.setPrefColumnCount(40);
        Button sign = Ui.primary(new Button("Review & sign"));
        sign.setOnAction(e -> Ui.attempt(this::submit));

        history.getColumns().add(Ui.col("Earlier counts", (StockCount s) -> s.countedAt().toLocalDate() + " " + s.countedAt().toLocalTime().withSecond(0)));
        history.getColumns().add(Ui.col("System (kg)", (StockCount s) -> Ui.kg(s.totalSystemG())));
        history.getColumns().add(Ui.col("Counted (kg)", (StockCount s) -> Ui.kg(s.totalCountedG())));
        history.getColumns().add(Ui.col("Difference (kg)", (StockCount s) -> signed(s.totalVarianceG())));
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setPlaceholder(new Label("No counts yet"));
        history.setPrefHeight(160);

        intro.getStyleClass().add("hint");
        var page = Ui.page(Ui.header("Stock count", null), intro, new ScaleDisplay(), table, new HBox(10, tare, read, reload), totals, comment, sign, new Separator(), Ui.section("Earlier counts"), history);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        setContent(scroll);
    }

    /** The counted amount as a text box that is always there. Typing updates the row straight away and marks it as typed (MANUAL). */
    private static final class CountCell extends TableCell<Row, String> {
        private final TextField field = new TextField();
        private boolean showing;     // true while the box is being filled from the row, so that is not mistaken for typing

        CountCell() {
            field.setPromptText("kg");
            field.textProperty().addListener((o, was, now) -> {
                if (showing) return;
                Row row = getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) return;
                row.counted.set(now == null ? "" : now.trim());
                row.source.set("MANUAL");
            });
        }

        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            if (empty) { setGraphic(null); return; }
            showing = true;
            try { if (!java.util.Objects.equals(field.getText(), value)) field.setText(value == null ? "" : value); }
            finally { showing = false; }
            setGraphic(field);
        }
    }

    private static boolean isZero(String signed) { return signed.replace("+", "").replace("-", "").replace("0", "").replace(".", "").isEmpty(); }

    /** +1.5 / -0.25 / 0 : kilograms from grams, with a sign. */
    static String signed(double grams) {
        String kg = Ui.kg(Math.abs(grams));
        if (kg.equals("0")) return "0";
        return (grams > 0 ? "+" : "-") + kg;
    }

    private void updateTotals() {
        double system = 0, counted = 0;
        int done = 0;
        for (Row r : table.getItems()) {
            system += r.container.currentVolumeMl();
            Double g = r.countedGrams();
            if (g != null) { counted += g; done++; }
        }
        totals.setText(table.getItems().isEmpty() ? "" : "Counted " + done + " of " + table.getItems().size() + " containers.   System total "
                + Ui.kg(system) + " kg   Counted total " + Ui.kg(counted) + " kg" + (done == table.getItems().size() ? "   Difference " + signed(counted - system) + " kg" : ""));
    }

    private void submit() {
        Session s = Session.get();
        List<StockEntry> entries = new ArrayList<>();
        double system = 0, counted = 0;
        StringBuilder differences = new StringBuilder();
        for (Row r : table.getItems()) {
            if (r.counted.get().isBlank()) throw new IllegalArgumentException("Count " + r.container.barcode() + " first (every IBC and drum is counted)");
            double g = Ui.gramsFromKg(r.counted.get(), "The count for " + r.container.barcode());
            entries.add(new StockEntry(r.container.barcode(), g, r.source.get().isEmpty() ? "MANUAL" : r.source.get()));
            system += r.container.currentVolumeMl();
            counted += g;
            if (!isZero(signed(g - r.container.currentVolumeMl())))
                differences.append("\n  ").append(r.container.barcode()).append(": ").append(signed(g - r.container.currentVolumeMl())).append(" kg");
        }
        if (entries.isEmpty()) throw new IllegalStateException("There is no IBC or drum at this site to count");
        if (!Ui.confirm("Stock count\n\nSystem total: " + Ui.kg(system) + " kg\nCounted total: " + Ui.kg(counted) + " kg\nDifference: " + signed(counted - system) + " kg"
                + (differences.length() == 0 ? "\n\nEvery container matches." : "\n\nDifferences:" + differences) + "\n\nSign to record this count?")) return;
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign the stock count");
        if (sig == null) return;
        service.submit(s.user(), s.site(), entries, comment.getText(), sig);
        comment.clear();
        refresh();
        Ui.info("Stock count recorded.");
    }

    @Override
    void refresh() {
        Session s = Session.get();
        colors = org.jai.fuelmarking.config.Tx.call(c -> new org.jai.fuelmarking.dao.MasterDataDao().rumColors(c));
        table.getItems().setAll(service.toCount(s.site().id()).stream().map(Row::new).toList());
        for (Row r : table.getItems()) r.counted.addListener((o, a, b) -> updateTotals());
        history.getItems().setAll(service.history(s.site().id()));
        updateTotals();
    }
}
