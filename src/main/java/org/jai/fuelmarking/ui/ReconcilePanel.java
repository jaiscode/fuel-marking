package org.jai.fuelmarking.ui;

import javafx.beans.property.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jai.fuelmarking.model.Records.Container;
import org.jai.fuelmarking.model.Records.SealedCheck;
import org.jai.fuelmarking.model.Records.StockEntry;
import org.jai.fuelmarking.service.IncidentService;
import org.jai.fuelmarking.service.ReconcileService;
import org.jai.fuelmarking.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The supervisor weighs (or types) every opened jerrican and sees at once how it compares with the system. A jerrican that is short
 * asks for its cause (spillage, leakage, other); the shortfall then becomes an incident when the reconciliation is signed. The sealed jerricans
 * are not weighed - that would break their seals - but counted: each is found with its seal intact, not found, or found with a broken seal.
 */
class ReconcilePanel extends VBox {
    private final ReconcileService service = new ReconcileService();
    private final TableView<Row> table = new TableView<>();
    private final Label totals = new Label();
    private final Button submit;
    private final Label title = new Label();
    private final TableView<SealedRow> sealedTable = new TableView<>();
    private final Label sealedSummary = new Label();
    private final VBox sealedBox = new VBox(8);
    private Map<String, String> colors = Map.of();

    ReconcilePanel(BiConsumer<List<StockEntry>, List<SealedCheck>> onSubmit, Runnable onCancel) {
        super(12);
        getStyleClass().add("card");
        title.getStyleClass().add("section-title");
        Label hint = new Label("Weigh each opened jerrican (tare an empty jerrican of the same kind first) or type the figure. "
                + "If one is short, say why: the shortfall is recorded as an incident and the system stock is corrected.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        table.getColumns().add(Ui.col("Jerrican", (Row r) -> r.container.barcode()));
        var rum = Ui.col("Rum type", (Row r) -> r.container.rumTypeCode() == null ? "" : r.container.rumTypeCode());
        RumSwatch.column(rum, r -> r.container.rumTypeCode(), () -> colors);
        table.getColumns().add(rum);
        table.getColumns().add(Ui.col("System (kg)", (Row r) -> Ui.kg(r.container.currentVolumeMl())));
        TableColumn<Row, String> counted = new TableColumn<>("Counted (kg)");
        counted.setCellValueFactory(cd -> cd.getValue().counted);
        counted.setCellFactory(c -> new CountCell());
        counted.setSortable(false);
        counted.setPrefWidth(150);
        table.getColumns().add(counted);
        TableColumn<Row, String> diff = new TableColumn<>("Difference (kg)");
        diff.setCellValueFactory(cd -> cd.getValue().difference);
        diff.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                setStyle(empty || v == null || v.isEmpty() || v.equals("0") ? "" : "-fx-text-fill: #b3261e; -fx-font-weight: bold;");
            }
        });
        diff.setPrefWidth(130);
        table.getColumns().add(diff);
        TableColumn<Row, String> cause = new TableColumn<>("Cause (if short)");
        cause.setCellValueFactory(cd -> cd.getValue().cause);
        cause.setCellFactory(c -> new CauseCell());
        cause.setSortable(false);
        cause.setPrefWidth(170);
        table.getColumns().add(cause);
        TableColumn<Row, String> note = new TableColumn<>("Note");
        note.setCellValueFactory(cd -> cd.getValue().note);
        note.setCellFactory(c -> new NoteCell());
        note.setSortable(false);
        note.setPrefWidth(260);
        table.getColumns().add(note);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("There is no opened jerrican at this site to reconcile"));
        table.setPrefHeight(230);
        table.setMinHeight(150);

        Button read = new Button("Read scale for selected");
        read.setOnAction(e -> {
            Row row = table.getSelectionModel().getSelectedItem();
            if (row == null) { Ui.error("Select the jerrican on the scale first."); return; }
            Weigh.readStable(read, kg -> {
                row.counted.set(Ui.kg(Weigh.roundGrams(kg)));
                row.source.set("SCALE");
            });
        });
        Button tare = new Button("Tare scale");
        tare.setOnAction(e -> Weigh.tare(tare));
        submit = Ui.primary(new Button("Review & sign"));
        submit.setOnAction(e -> Ui.attempt(() -> onSubmit.accept(entries(), sealedChecks())));
        Button cancel = new Button("Back to the shift");
        cancel.setOnAction(e -> onCancel.run());
        HBox buttons = new HBox(10, tare, read, new Region(), cancel, submit);
        HBox.setHgrow(buttons.getChildren().get(2), Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER_LEFT);

        // ---- the sealed jerricans: counted, each seal looked at (nothing is weighed)
        Label sealedHeading = Ui.section("Sealed jerricans - count them and look at each seal");
        Label sealedHint = new Label("Do not break the seals. For each sealed jerrican say whether it is there with its seal intact, is missing, or is there with a broken seal "
                + "(or a seal that is not the one on record). A missing jerrican is recorded as an incident; a broken seal is flagged for head office.");
        sealedHint.getStyleClass().add("hint");
        sealedHint.setWrapText(true);
        sealedTable.getColumns().add(Ui.col("Sealed jerrican", (SealedRow r) -> r.container.barcode()));
        var sealedRum = Ui.col("Rum type", (SealedRow r) -> r.container.rumTypeCode() == null ? "" : r.container.rumTypeCode());
        RumSwatch.column(sealedRum, r -> r.container.rumTypeCode(), () -> colors);
        sealedTable.getColumns().add(sealedRum);
        sealedTable.getColumns().add(Ui.col("In it (kg)", (SealedRow r) -> Ui.kg(r.container.currentVolumeMl())));
        sealedTable.getColumns().add(Ui.col("Seals", (SealedRow r) -> InventoryTab.seals(r.container)));
        TableColumn<SealedRow, String> result = new TableColumn<>("Check");
        result.setCellValueFactory(cd -> cd.getValue().result);
        result.setCellFactory(c -> new ResultCell());
        result.setSortable(false);
        result.setPrefWidth(230);
        sealedTable.getColumns().add(result);
        TableColumn<SealedRow, String> sealedNote = new TableColumn<>("Note");
        sealedNote.setCellValueFactory(cd -> cd.getValue().note);
        sealedNote.setCellFactory(c -> new SealedNoteCell());
        sealedNote.setSortable(false);
        sealedNote.setPrefWidth(240);
        sealedTable.getColumns().add(sealedNote);
        sealedTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sealedTable.setPrefHeight(190);
        sealedTable.setMinHeight(110);
        Button allFound = new Button("All found, seals intact");
        allFound.setOnAction(e -> sealedTable.getItems().stream().filter(r -> r.result.get().isEmpty()).forEach(r -> r.result.set("FOUND")));
        HBox sealedBar = new HBox(12, allFound, sealedSummary);
        sealedBar.setAlignment(Pos.CENTER_LEFT);
        sealedBox.getChildren().addAll(new Separator(), sealedHeading, sealedHint, sealedTable, sealedBar);
        sealedSummary.getStyleClass().add("hint");

        getChildren().addAll(title, hint, new ScaleDisplay(), table, totals, sealedBox, buttons);
    }

    void open() {
        title.setText("Reconcile the jerricans");
        load();
    }

    void load() {
        colors = org.jai.fuelmarking.config.Tx.call(c -> new org.jai.fuelmarking.dao.MasterDataDao().rumColors(c));
        table.getItems().setAll(service.toReconcile(Session.get().site().id()).stream().map(Row::new).toList());
        for (Row r : table.getItems()) r.counted.addListener((o, a, b) -> updateTotals());
        sealedTable.getItems().setAll(service.sealedToCheck(Session.get().site().id()).stream().map(SealedRow::new).toList());
        for (SealedRow r : sealedTable.getItems()) r.result.addListener((o, a, b) -> updateSealedSummary());
        boolean anySealed = !sealedTable.getItems().isEmpty();
        submit.setDisable(table.getItems().isEmpty() && !anySealed);          // nothing to reconcile
        sealedBox.setVisible(anySealed);
        sealedBox.setManaged(anySealed);
        table.setPlaceholder(new Label(anySealed ? "No jerrican is open - only the sealed ones are checked" : "There is no opened jerrican at this site to reconcile"));
        updateTotals();
        updateSealedSummary();
    }

    /** Jerricans to reconcile: opened ones to weigh, sealed ones to count. */
    boolean hasJerricans() {
        return !service.toReconcile(Session.get().site().id()).isEmpty() || !service.sealedToCheck(Session.get().site().id()).isEmpty();
    }

    private void updateSealedSummary() {
        long n = sealedTable.getItems().size(), found = sealedTable.getItems().stream().filter(r -> "FOUND".equals(r.result.get())).count();
        long missing = sealedTable.getItems().stream().filter(r -> "MISSING".equals(r.result.get())).count();
        long broken = sealedTable.getItems().stream().filter(r -> "SEAL_BROKEN".equals(r.result.get())).count();
        long left = n - found - missing - broken;
        sealedSummary.setText(n == 0 ? "" : n + " sealed in the system:  " + found + " found" + (missing > 0 ? ",  " + missing + " not found" : "")
                + (broken > 0 ? ",  " + broken + " with a broken seal" : "") + (left > 0 ? ",  " + left + " still to check" : ""));
    }

    /** What was checked for the sealed jerricans, ready for the service; refuses while one is still unchecked. */
    List<SealedCheck> sealedChecks() {
        List<SealedCheck> out = new ArrayList<>();
        for (SealedRow r : sealedTable.getItems()) {
            if (r.result.get().isEmpty()) throw new IllegalArgumentException("Check " + r.container.barcode() + " first (found with its seal intact, not found, or seal broken)");
            out.add(new SealedCheck(r.container.barcode(), r.result.get(), r.note.get().isBlank() ? null : r.note.get().trim()));
        }
        return out;
    }

    private void updateTotals() {
        double system = 0, counted = 0;
        int done = 0, short_ = 0;
        for (Row r : table.getItems()) {
            system += r.container.currentVolumeMl();
            Double g = r.countedGrams();
            if (g != null) { counted += g; done++; if (r.shortfall.get()) short_++; }
        }
        totals.setText(table.getItems().isEmpty() ? "" : "Counted " + done + " of " + table.getItems().size() + " jerricans.   System total "
                + Ui.kg(system) + " kg   Counted total " + Ui.kg(counted) + " kg"
                + (short_ > 0 ? "   " + short_ + " short - choose a cause for each" : ""));
    }

    /** What was entered, ready for the service; refuses while a jerrican is still uncounted. */
    List<StockEntry> entries() {
        List<StockEntry> out = new ArrayList<>();
        for (Row r : table.getItems()) {
            if (r.counted.get().isBlank()) throw new IllegalArgumentException("Count " + r.container.barcode() + " first (every opened jerrican is counted)");
            double g = Ui.gramsFromKg(r.counted.get(), "The count for " + r.container.barcode());
            out.add(new StockEntry(r.container.barcode(), g, r.source.get().isEmpty() ? "MANUAL" : r.source.get(),
                    r.shortfall.get() && !r.cause.get().isEmpty() ? r.cause.get() : null, r.note.get().isBlank() ? null : r.note.get().trim()));
        }
        return out;
    }

    /** A summary of what signing will do, for the confirmation box. */
    String summary(List<StockEntry> entries, List<SealedCheck> checks) {
        String sealedLines = sealedSummaryLines(checks);
        String opened = summary(entries);
        return sealedLines.isEmpty() ? opened : opened + "\n\n" + sealedLines;
    }

    private String sealedSummaryLines(List<SealedCheck> checks) {
        if (checks == null || checks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Sealed jerricans: " + checks.size() + " checked");
        int missing = 0, broken = 0;
        for (SealedCheck c : checks) {
            if ("MISSING".equals(c.result())) { sb.append("\n  ").append(c.barcode()).append(": NOT FOUND (recorded as an incident, marked missing)"); missing++; }
            else if ("SEAL_BROKEN".equals(c.result())) { sb.append("\n  ").append(c.barcode()).append(": seal broken or different (flagged for head office)"); broken++; }
        }
        if (missing == 0 && broken == 0) sb.append(" - all found with their seals intact.");
        return sb.toString();
    }

    String summary(List<StockEntry> entries) {
        StringBuilder sb = new StringBuilder();
        int incidents = 0;
        for (StockEntry e : entries) {
            Container k = table.getItems().stream().map(r -> r.container).filter(c -> c.barcode().equals(e.barcode())).findFirst().orElseThrow();
            double diff = e.countedG() - k.currentVolumeMl();
            if (Math.abs(diff) < ReconcileService.TOLERANCE_G) continue;
            sb.append("\n  ").append(k.barcode()).append(": ").append(StockCountTab.signed(diff)).append(" kg");
            if (diff < 0) {
                if (e.cause() == null) throw new IllegalArgumentException(k.barcode() + " is short - choose a cause (spillage, leakage or other)");
                sb.append(" - ").append(e.cause().toLowerCase()).append(" (incident)");
                incidents++;
            } else sb.append(" - more than the system holds");
        }
        return sb.length() == 0 ? "Every jerrican matches the system." : "Differences:" + sb + "\n\n" + incidents + " incident(s) will be recorded.";
    }

    /** A sealed jerrican being checked: what was found (empty until chosen) and a note. */
    static final class SealedRow {
        final Container container;
        final StringProperty result = new SimpleStringProperty("");      // FOUND, MISSING or SEAL_BROKEN
        final StringProperty note = new SimpleStringProperty("");

        SealedRow(Container c) { this.container = c; }
    }

    /** The check for a sealed jerrican, chosen from a short list. */
    private static final class ResultCell extends TableCell<SealedRow, String> {
        private final ComboBox<String> box = new ComboBox<>();
        private boolean showing;
        private SealedRow bound;

        ResultCell() {
            box.getItems().setAll("FOUND", "MISSING", "SEAL_BROKEN");
            box.setPromptText("Choose");
            box.setMaxWidth(Double.MAX_VALUE);
            box.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(String s) { return s == null ? "" : switch (s) { case "FOUND" -> "Found, seal intact"; case "MISSING" -> "Not found"; default -> "Seal broken or different"; }; }
                @Override public String fromString(String s) { return s; }
            });
            box.valueProperty().addListener((o, a, b) -> { if (!showing && bound != null) bound.result.set(b == null ? "" : b); });
        }

        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            SealedRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
            bound = row;
            if (row == null) { setGraphic(null); return; }
            showing = true;
            try { box.setValue(value == null || value.isEmpty() ? null : value); } finally { showing = false; }
            setGraphic(box);
        }
    }

    /** A note, needed when the jerrican was not found or its seal is not right. */
    private static final class SealedNoteCell extends TableCell<SealedRow, String> {
        private final TextField field = new TextField();
        private boolean showing;
        private SealedRow bound;

        SealedNoteCell() {
            field.setPromptText("What did you find?");
            field.textProperty().addListener((o, a, b) -> { if (!showing && bound != null) bound.note.set(b == null ? "" : b); });
        }

        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            field.disableProperty().unbind();
            SealedRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
            bound = row;
            if (row == null) { setGraphic(null); return; }
            showing = true;
            try { if (!java.util.Objects.equals(field.getText(), value)) field.setText(value == null ? "" : value); } finally { showing = false; }
            field.disableProperty().bind(row.result.isEqualTo("FOUND").or(row.result.isEmpty()));
            setGraphic(field);
        }
    }

    static final class Row {
        final Container container;
        final StringProperty counted = new SimpleStringProperty("");
        final StringProperty source = new SimpleStringProperty("");
        final StringProperty difference = new SimpleStringProperty("");
        final StringProperty cause = new SimpleStringProperty("");
        final StringProperty note = new SimpleStringProperty("");
        final BooleanProperty shortfall = new SimpleBooleanProperty();
        final BooleanProperty surplus = new SimpleBooleanProperty();

        Row(Container c) {
            this.container = c;
            counted.addListener((o, a, b) -> {
                Double g = countedGrams();
                double d = g == null ? 0 : g - c.currentVolumeMl();
                difference.set(g == null ? "" : StockCountTab.signed(d));
                shortfall.set(g != null && d <= -ReconcileService.TOLERANCE_G);
                surplus.set(g != null && d >= ReconcileService.TOLERANCE_G);
                if (!shortfall.get()) cause.set("");
            });
        }

        Double countedGrams() {
            try { return Ui.gramsFromKg(counted.get(), ""); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** The counted amount as a box that is always there: typing counts at once and marks the figure as typed. */
    private static final class CountCell extends TableCell<Row, String> {
        private final TextField field = new TextField();
        private boolean showing;

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

    /** The cause is only available for a jerrican that is short. */
    private static final class CauseCell extends TableCell<Row, String> {
        private final ComboBox<String> box = new ComboBox<>();
        private boolean showing;
        private Row bound;

        CauseCell() {
            box.getItems().setAll(IncidentService.TYPES);
            box.setPromptText("Choose cause");
            box.setMaxWidth(Double.MAX_VALUE);
            box.valueProperty().addListener((o, a, b) -> {
                if (showing || bound == null) return;
                bound.cause.set(b == null ? "" : b);
            });
        }

        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            box.disableProperty().unbind();
            Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
            bound = row;
            if (row == null) { setGraphic(null); return; }
            showing = true;
            try { box.setValue(value == null || value.isEmpty() ? null : value); } finally { showing = false; }
            box.disableProperty().bind(row.shortfall.not());
            setGraphic(box);
        }
    }

    /** A note is available when there is a difference to explain. */
    private static final class NoteCell extends TableCell<Row, String> {
        private final TextField field = new TextField();
        private boolean showing;
        private Row bound;

        NoteCell() {
            field.setPromptText("What happened?");
            field.textProperty().addListener((o, a, b) -> {
                if (showing || bound == null) return;
                bound.note.set(b == null ? "" : b);
            });
        }

        @Override protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            field.disableProperty().unbind();
            Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
            bound = row;
            if (row == null) { setGraphic(null); return; }
            showing = true;
            try { if (!java.util.Objects.equals(field.getText(), value)) field.setText(value == null ? "" : value); } finally { showing = false; }
            field.disableProperty().bind(row.shortfall.or(row.surplus).not());
            setGraphic(field);
        }
    }
}
