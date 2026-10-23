package org.jai.fuelmarking.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.ReconcileService;
import org.jai.fuelmarking.service.ShiftService;
import org.jai.fuelmarking.session.Session;

class ShiftTab extends RefreshableTab {
    private final ShiftService service = new ShiftService();
    private final Label status = new Label();
    private final Label statusHint = new Label();
    private final Circle dot = new Circle(6);
    private final TextField notes = new TextField();
    private final Button start = Ui.primary(new Button("Start shift"));
    private final ComboBox<Location> location = new ComboBox<>();   // where the site is working: required at a mobile site, optional at a fixed one
    private final Button end = new Button("End shift");
    private final Button reconcileNow = new Button("Reconcile stock");   // opens the Reconcile screen
    private final ReconcileService reconcileService = new ReconcileService();
    private final Label lastReconciled = new Label();
    private final ScrollPane scroll;
    private final Runnable onShiftChanged;
    private final Label opening = new Label(), opened = new Label(), dosed = new Label(), losses = new Label(), closedBack = new Label();
    private final Label expected = new Label(), actual = new Label(), varianceValue = new Label(), varianceNote = new Label();
    private final VBox varianceBox = new VBox(2, label("VARIANCE"), varianceValue, varianceNote);
    private final VBox reconciliation = new VBox(14);
    private final Label trucksCheck = new Label(), jerricansCheck = new Label();
    private final VBox checklist = new VBox(8);

    /** @param openReconcile takes the supervisor to the Reconcile screen */
    ShiftTab(Runnable onShiftChanged, Runnable openReconcile) {
        super("Shift");
        this.onShiftChanged = onShiftChanged;
        notes.setPromptText("Closing notes (optional)");
        start.setOnAction(e -> Ui.attempt(() -> {
            Session s = Session.get();
            s.setShift(service.start(s.user(), s.site(), location.getValue()));
            onShiftChanged.run();
            refresh();
        }));
        end.setOnAction(e -> Ui.attempt(this::endShift));
        reconcileNow.setOnAction(e -> openReconcile.run());
        lastReconciled.getStyleClass().add("hint");

        status.getStyleClass().add("section-title");
        statusHint.getStyleClass().add("hint");
        VBox statusText = new VBox(2, status, statusHint);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox statusRow = new HBox(14, dot, statusText, grow, location, start, reconcileNow, end);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getStyleClass().add("card");

        Label checkTitle = new Label("Before you can close the shift");
        checkTitle.getStyleClass().add("section-title");
        trucksCheck.setWrapText(true);
        jerricansCheck.setWrapText(true);
        checklist.getStyleClass().add("card");
        checklist.getChildren().addAll(checkTitle, trucksCheck, jerricansCheck);

        HBox figures = new HBox(14, figure("OPENING", "Rum in opened jerricans at the start", opening),
                figure("OPENED TODAY", "Jerricans opened this shift", opened),
                figure("DOSED INTO TRUCKS", "Weighed doses given", dosed),
                figure("INCIDENT LOSSES", "Spills and breakages", losses),
                figure("CLOSED BACK", "Sealed again with rum in", closedBack));
        figures.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        expected.getStyleClass().add("big-number");
        actual.getStyleClass().add("big-number");
        varianceValue.getStyleClass().add("big-number");
        varianceNote.getStyleClass().add("hint");
        varianceBox.getStyleClass().add("variance");
        HBox result = new HBox(14, new VBox(2, label("EXPECTED NOW"), expected, hint("Opening + opened - dosed - losses - closed back")),
                new VBox(2, label("ACTUAL IN OPENED JERRICANS"), actual, hint("What the jerricans really hold")), varianceBox);
        result.getChildren().forEach(n -> { HBox.setHgrow(n, Priority.ALWAYS); ((Region) n).setMaxWidth(Double.MAX_VALUE); });
        result.getStyleClass().add("card");

        Label title = new Label("Rum reconciliation");
        title.getStyleClass().add("section-title");
        // the user chooses how amounts are shown: grams or kilograms
        ToggleButton inGrams = new ToggleButton("g"), inKg = new ToggleButton("kg");
        ToggleGroup unit = new ToggleGroup();
        for (ToggleButton t : java.util.List.of(inGrams, inKg)) { t.setToggleGroup(unit); t.getStyleClass().add("segment"); t.setMinWidth(54); }
        (Ui.rumInKg ? inKg : inGrams).setSelected(true);
        unit.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { was.setSelected(true); return; }                       // one unit is always chosen
            Ui.rumInKg = now == inKg;
            showFigures();
        });
        HBox unitSwitch = new HBox(0, inGrams, inKg);
        unitSwitch.getStyleClass().add("segments");
        Tooltip.install(unitSwitch, new Tooltip("Show the amounts in grams or in kilograms"));
        Region titleGrow = new Region();
        HBox.setHgrow(titleGrow, Priority.ALWAYS);
        HBox titleRow = new HBox(12, title, titleGrow, new Label("Show in"), unitSwitch);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        ((Label) titleRow.getChildren().get(2)).getStyleClass().add("hint");
        reconciliation.getChildren().addAll(titleRow, lastReconciled, figures, result, notes);
        scroll = new ScrollPane(Ui.page(statusRow, checklist, reconciliation));
        scroll.setFitToWidth(true);
        setContent(scroll);
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("kpi-label");
        return l;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        return l;
    }

    private static VBox figure(String name, String note, Label value) { return Ui.kpi(name, value, note); }

    private void endShift() {
        Session s = Session.get();
        var blockers = service.closeBlockers(s.shift());
        if (!blockers.isEmpty()) throw new IllegalStateException("The shift cannot be closed yet: " + String.join("; ", blockers));
        Reconciliation preview = service.reconcile(s.shift());
        if (!Ui.confirm("Closing the shift.\nExpected rum in opened jerricans: " + Ui.rumAmount(preview.expectedClosingMl())
                + "\nActual: " + Ui.rumAmount(preview.actualClosingMl()) + "\nVariance: " + Ui.rumAmount(preview.varianceMl())
                + "\n\nClose the shift?")) return;
        service.end(s.user(), s.shift(), notes.getText());
        s.setShift(null);
        notes.clear();
        onShiftChanged.run();
        refresh();
        Ui.info("Shift closed.");
    }

    @Override
    void refresh() {
        Session s = Session.get();
        boolean open = s.shift() != null;
        start.setDisable(open);
        location.setVisible(!open);
        location.setManaged(!open);
        boolean mobile = "MOBILE".equals(s.site().siteType());
        location.setPromptText(mobile ? "Where are you working? (required)" : "Location (optional)");
        Location chosen = location.getValue();
        location.getItems().setAll(org.jai.fuelmarking.config.Tx.<java.util.List<Location>>call(c -> new org.jai.fuelmarking.dao.MasterDataDao().locations(c)));
        if (chosen != null && location.getItems().contains(chosen)) location.setValue(chosen);
        end.setDisable(true);
        reconcileNow.setDisable(!open);
        checklist.setVisible(open);
        checklist.setManaged(open);
        dot.getStyleClass().setAll("status-dot", open ? "on" : "off");
        status.setText(open ? "Shift OPEN since " + s.shift().startedAt().toLocalTime().withNano(0)
                + (s.shift().locationName() == null ? "" : " at " + s.shift().locationName()) : "No shift open");
        statusHint.setText(open ? "Register, dose and verify trucks. End the shift when the work is done."
                : mobile ? "This is a mobile site: choose where you are working, then start the shift." : "Start a shift to register trucks and open jerricans.");
        reconciliation.setVisible(open);
        reconciliation.setManaged(open);
        if (!open) return;
        showChecklist(s);
        reconcileService.history(s.site().id()).stream().filter(c -> s.shift().id().equals(c.shiftId())).findFirst().ifPresentOrElse(
                c -> lastReconciled.setText("Last reconciled at " + c.countedAt().toLocalTime().withNano(0).withSecond(0)
                        + (Math.abs(c.totalVarianceG()) < ReconcileService.TOLERANCE_G ? " - everything matched" : " - difference " + StockCountTab.signed(c.totalVarianceG()) + " kg")),
                () -> lastReconciled.setText("Not reconciled yet this shift. Reconcile any time, or when you close the shift."));
        shown = service.reconcile(s.shift());
        showFigures();
    }

    private Reconciliation shown;

    /** The figures in the unit the user chose (grams or kilograms). */
    private void showFigures() {
        Reconciliation r = shown;
        if (r == null) return;
        opening.setText(Ui.rumAmount(r.openingMl()));
        opened.setText(Ui.rumAmount(r.openedDuringShiftMl()));
        dosed.setText(Ui.rumAmount(r.dispensedMl()));
        losses.setText(Ui.rumAmount(r.incidentMl()));
        closedBack.setText(Ui.rumAmount(r.closedBackMl()));
        expected.setText(Ui.rumAmount(r.expectedClosingMl()));
        actual.setText(Ui.rumAmount(r.actualClosingMl()));
        double v = r.varianceMl();
        varianceValue.setText((v > 0 ? "+" : "") + Ui.rumAmount(v));
        boolean ok = Math.abs(v) < 0.05;
        varianceNote.setText(ok ? "Balanced - nothing unaccounted for" : v < 0 ? "Less rum than expected - check for unrecorded loss" : "More rum than expected - check the weights");
        varianceBox.getStyleClass().removeAll("ok", "bad");
        varianceBox.getStyleClass().add(ok ? "ok" : "bad");
    }

    /** Two things must be done before the shift can be closed; each is shown as done or still to do, and End shift unlocks when both are done. */
    private void showChecklist(Session s) {
        long pending = service.unverifiedMarkings(s.shift());
        var open = reconcileService.toReconcile(s.site().id()).stream().map(org.jai.fuelmarking.model.Records.Container::barcode).toList();
        tick(trucksCheck, pending == 0, pending == 0 ? "Every truck is verified."
                : pending + (pending == 1 ? " truck is" : " trucks are") + " not verified yet - verify " + (pending == 1 ? "it" : "them") + " on the Verify tab.");
        tick(jerricansCheck, open.isEmpty(), open.isEmpty() ? "No jerrican is left open."
                : "Still open: " + String.join(", ", open) + ". In the Jerricans menu, close each one back with a seal (Close jerrican, if it still holds rum) or declare it empty (Declare empty).");
        end.setDisable(pending > 0 || !open.isEmpty());
    }

    private static void tick(Label l, boolean done, String text) {
        l.setText((done ? "\u2714  " : "\u2716  ") + text);
        l.getStyleClass().removeAll("check-ok", "check-todo");
        l.getStyleClass().add(done ? "check-ok" : "check-todo");
    }

}
