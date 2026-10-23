package org.jai.fuelmarking.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.config.YamlConfigurationLoader.FuelMarking;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.Records.MarkingDose;
import org.jai.fuelmarking.model.Records.RequiredDose;
import org.jai.fuelmarking.model.Records.RumType;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.service.MarkingService;
import org.jai.fuelmarking.session.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dosing is weighed, and done one compartment at a time: choose a truck, then each of its compartments in turn. For a compartment, put an empty small
 * container on the scale and tare it, draw the dose from an opened jerrican of the rum type that compartment's fuel is marked with until the weight
 * reaches the target, read the scale, scan the jerrican and confirm. The target and the measured weight are shown side by side, with a gauge.
 */
class DoseTab extends RefreshableTab {
    private final MarkingService service = new MarkingService();
    private final ListView<TruckMarking> queue = new ListView<>();
    private final ListView<RequiredDose> compartmentList = new ListView<>();
    private final Label truckTitle = new Label("Choose a truck"), truckInfo = new Label(), truckTotals = new Label();

    // the dosing panel
    private final Label panelTitle = new Label(), targetValue = new Label("-"), targetRange = new Label(), measuredValue = new Label("-"), feedback = new Label();
    private final ProgressBar gauge = new ProgressBar(0);
    private final TextField measured = new TextField();
    private final TextField jerrican = new TextField();
    private final Button confirm = Ui.primary(new Button("Confirm dose & sign"));
    private final VBox dosingCard = new VBox(14), doneCard = new VBox(8);
    private final Label doneText = new Label();
    private final VBox truckCard = new VBox(10);

    private Map<String, String> rumNames = Map.of(), rumColors = Map.of();
    private String readFromScale;      // the text last filled in from the scale, to tell scale readings from typed ones

    DoseTab() {
        super("Dose");

        // ---- left: trucks waiting for their rum
        queue.getStyleClass().add("jar-list");
        queue.setCellFactory(lv -> new TruckCell());
        queue.setPlaceholder(new Label("Nothing waiting"));
        queue.setPrefHeight(520);
        VBox.setVgrow(queue, Priority.ALWAYS);
        queue.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> truckChosen(t));
        VBox left = Ui.card(Ui.section("Waiting for rum"), queue);
        left.setPrefWidth(420);
        left.setMinWidth(380);

        // ---- right: the truck, its compartments, and the dosing panel
        truckTitle.getStyleClass().add("section-title");
        truckInfo.getStyleClass().add("hint");
        truckTotals.getStyleClass().add("hint");
        truckTotals.setWrapText(true);
        compartmentList.getStyleClass().add("jar-list");
        compartmentList.setCellFactory(lv -> new CompartmentCell());
        compartmentList.setPlaceholder(new Label("Choose a truck to see its compartments"));
        compartmentList.setPrefHeight(230);
        compartmentList.setMinHeight(120);
        compartmentList.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> compartmentChosen(r));
        truckCard.getChildren().addAll(truckTitle, truckInfo, compartmentList, truckTotals);
        truckCard.getStyleClass().add("card");

        panelTitle.getStyleClass().add("section-title");
        targetValue.getStyleClass().add("dose-big");
        measuredValue.getStyleClass().add("dose-big");
        targetRange.getStyleClass().add("hint");
        feedback.getStyleClass().add("dose-feedback");
        feedback.setWrapText(true);
        gauge.setMaxWidth(Double.MAX_VALUE);
        gauge.setPrefHeight(14);
        VBox target = new VBox(2, Ui.caption("TARGET"), targetValue, targetRange);
        VBox measure = new VBox(2, Ui.caption("MEASURED"), measuredValue, feedback);
        target.getStyleClass().add("dose-box"); measure.getStyleClass().add("dose-box");
        HBox.setHgrow(target, Priority.ALWAYS); HBox.setHgrow(measure, Priority.ALWAYS);
        HBox figures = new HBox(14, target, measure);

        measured.setPromptText("Dose in grams (from the scale)");
        measured.textProperty().addListener((o, a, b) -> gaugeChanged());
        jerrican.setPromptText("Scan the opened jerrican you are dosing from");
        Button tare = new Button("Tare scale");
        tare.setOnAction(e -> Weigh.tare(tare));
        Button read = new Button("Read scale");
        read.setOnAction(e -> Weigh.readStable(read, kg -> {
            String text = Weigh.formatGrams(Weigh.roundGrams(kg));
            measured.setText(text);
            readFromScale = text;
        }));
        HBox weigh = new HBox(10, tare, read);
        Label steps = new Label("1. Put the empty small container on the scale and press Tare.   2. Draw the rum from the jerrican until the weight reaches the target.   "
                + "3. Press Read scale, scan the jerrican, then confirm and sign.");
        steps.getStyleClass().add("hint");
        steps.setWrapText(true);
        confirm.setMaxWidth(Double.MAX_VALUE);
        confirm.setOnAction(e -> Ui.attempt(this::confirmDose));
        dosingCard.getChildren().addAll(panelTitle, figures, gauge, new ScaleDisplay(), weigh, Ui.field("Measured dose (g)", measured), Ui.field("Jerrican", jerrican), steps, confirm);
        dosingCard.getStyleClass().add("card");

        doneText.setWrapText(true);
        doneCard.getChildren().addAll(Ui.section("This compartment is dosed"), doneText);
        doneCard.getStyleClass().add("card");

        VBox right = new VBox(14, truckCard, dosingCard, doneCard);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(14, left, right);
        showPanel(null, null);
        setContent(new ScrollPane(Ui.page(Ui.header("Dose", "Dose a truck one compartment at a time. Each compartment has its own target, and the rum comes from a jerrican of the rum type its fuel is marked with."), body)) {{ setFitToWidth(true); }});
    }

    private String rumName(String code) { return rumNames.getOrDefault(code, code); }

    // ---------------------------------------------------------------- cells

    /** A truck waiting: number, who it is for, and how far its compartments are dosed. */
    private final class TruckCell extends ListCell<TruckMarking> {
        @Override protected void updateItem(TruckMarking t, boolean empty) {
            super.updateItem(t, empty);
            if (empty || t == null) { setGraphic(null); setText(null); return; }
            int total = t.getRequiredDoses().size(), done = total - t.remainingDoses().size();
            Label number = new Label(t.getTruckNumber() + (t.getTrailerNumber() == null ? "" : " + " + t.getTrailerNumber()));
            number.getStyleClass().addAll("jar-title", "mono");
            Label sub = new Label(t.getOilMarketingCompany() + "  -  " + Ui.fmt(t.getDeclaredVolume()) + " L");
            sub.getStyleClass().add("hint");
            Label progress = new Label(done + " of " + total + " compartments dosed");
            progress.getStyleClass().add("hint");
            Label target = new Label(Ui.fmt(t.calculateRequiredRum()) + " g");
            target.getStyleClass().add("jar-title");
            Label targetCaption = new Label("to dose");
            targetCaption.getStyleClass().add("hint");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            VBox amount = new VBox(1, target, targetCaption);
            amount.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, new VBox(1, number, sub, progress), grow, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 10, 8, 10));
            row.getStyleClass().add("jar-card");
            setGraphic(row);
            setText(null);
        }
    }

    /** A compartment: its fuel and rum, the target weight, the measured weight once dosed, and whether it is done. */
    private final class CompartmentCell extends ListCell<RequiredDose> {
        @Override protected void updateItem(RequiredDose r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setGraphic(null); setText(null); return; }
            Optional<MarkingDose> dose = current == null ? Optional.empty() : current.getDoses().stream().filter(d -> d.compartment().equals(r.compartment())).findFirst();
            Label name = new Label("ALL".equals(r.compartment()) ? "Truck" : r.compartment());
            name.getStyleClass().add("jar-title");
            name.setMinWidth(44);
            Label fuel = new Label((r.fuelType() == null ? "" : r.fuelType() + "  -  ") + Ui.fmt(r.litres()) + " L");
            fuel.getStyleClass().add("jar-title");
            HBox rum = new HBox(6, RumSwatch.square(rumColors.get(r.rumType())), new Label(rumName(r.rumType())));
            rum.setAlignment(Pos.CENTER_LEFT);
            ((Label) rum.getChildren().get(1)).getStyleClass().add("hint");
            VBox what = new VBox(2, fuel, rum);
            Label target = new Label(Ui.fmt(r.requiredG()) + " g");
            target.getStyleClass().add("jar-title");
            Label meas = new Label(dose.map(d -> Ui.fmt(d.measuredG()) + " g").orElse("-"));
            meas.getStyleClass().add("jar-title");
            Label pill = new Label(dose.isPresent() ? "Dosed" : "To dose");
            pill.getStyleClass().addAll("pill", dose.isPresent() ? "pill-ok" : "pill-opened");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(14, name, what, grow, new VBox(1, Ui.caption("TARGET"), target), new VBox(1, Ui.caption("MEASURED"), meas), pill);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 10, 8, 10));
            row.getStyleClass().add("jar-card");
            setGraphic(row);
            setText(null);
        }
    }

    // ---------------------------------------------------------------- choosing

    private void truckChosen(TruckMarking t) {
        current = t;
        compartmentList.getItems().setAll(t == null ? List.of() : t.getRequiredDoses());
        compartmentList.setPrefHeight(t == null ? 130 : Math.max(130, Math.min(420, t.getRequiredDoses().size() * 62 + 8)));      // as tall as its compartments
        if (t == null) {
            truckTitle.setText("Choose a truck");
            truckInfo.setText("");
            truckTotals.setText("");
            showPanel(null, null);
            return;
        }
        truckTitle.setText("Truck " + t.getTruckNumber());
        truckInfo.setText(t.getOrderNumber() + "  -  " + t.getOilMarketingCompany() + (t.getTrailerNumber() == null ? "" : "  -  trailer " + t.getTrailerNumber()));
        double measuredSoFar = t.getDoses().stream().mapToDouble(MarkingDose::measuredG).sum();
        truckTotals.setText("Truck total: " + Ui.fmt(t.calculateRequiredRum()) + " g to dose, " + Ui.fmt(measuredSoFar) + " g dosed so far.   Each compartment is dosed on its own, within " + Ui.fmt(FuelMarking.getDoseTolerancePercent()) + "% of its target.");
        var next = t.remainingDoses();
        if (!next.isEmpty()) compartmentList.getSelectionModel().select(next.get(0));      // the first one still to dose
        else showPanel(null, null);
    }

    private TruckMarking current;

    private void compartmentChosen(RequiredDose r) {
        showPanel(current, r);
    }

    private RequiredDose active() { return compartmentList.getSelectionModel().getSelectedItem(); }

    private void showPanel(TruckMarking t, RequiredDose r) {
        boolean dosed = t != null && r != null && t.getDoses().stream().anyMatch(d -> d.compartment().equals(r.compartment()));
        boolean todo = t != null && r != null && !dosed;
        dosingCard.setVisible(todo); dosingCard.setManaged(todo);
        doneCard.setVisible(dosed); doneCard.setManaged(dosed);
        if (dosed) {
            MarkingDose d = t.getDoses().stream().filter(x -> x.compartment().equals(r.compartment())).findFirst().orElseThrow();
            doneText.setText(String.format(Locale.ROOT, "Compartment %s took %.1f g (target %.1f g), %s.", r.compartment(), d.measuredG(), d.requiredG(),
                    "SCALE".equals(d.source()) ? "weighed on the scale" : "typed in by the operator"));
        }
        if (todo) {
            double tol = r.requiredG() * FuelMarking.getDoseTolerancePercent() / 100.0;
            panelTitle.setText("Dose " + ("ALL".equals(r.compartment()) ? "the truck" : "compartment " + r.compartment()) + (r.fuelType() == null ? "" : " - " + r.fuelType()) + " - " + rumName(r.rumType()));
            targetValue.setText(Ui.fmt(r.requiredG()) + " g");
            targetRange.setText(String.format(Locale.ROOT, "accepted %.1f to %.1f g", r.requiredG() - tol, r.requiredG() + tol));
            jerrican.setPromptText("Scan an opened jerrican of " + rumName(r.rumType()));
            measured.clear(); jerrican.clear(); readFromScale = null;
            gaugeChanged();
        }
    }

    /** The measured weight against the target: the big number, a gauge, and what to do next. */
    private void gaugeChanged() {
        RequiredDose r = active();
        gauge.getStyleClass().removeAll("gauge-ok", "gauge-low", "gauge-high");
        feedback.getStyleClass().removeAll("fb-ok", "fb-low", "fb-high");
        if (r == null) return;
        Double g = parse(measured.getText());
        if (g == null) {
            measuredValue.setText("-");
            gauge.setProgress(0);
            feedback.setText("Draw the rum, then read the scale.");
            return;
        }
        double target = r.requiredG(), tol = target * FuelMarking.getDoseTolerancePercent() / 100.0;
        measuredValue.setText(Ui.fmt(g) + " g");
        gauge.setProgress(target <= 0 ? 0 : Math.min(1.0, g / target));
        if (g < target - tol - 1e-9) {
            gauge.getStyleClass().add("gauge-low"); feedback.getStyleClass().add("fb-low");
            feedback.setText(String.format(Locale.ROOT, "%.1f g short of the target - add about %.1f g more.", target - g, target - g));
        } else if (g > target + tol + 1e-9) {
            gauge.getStyleClass().add("gauge-high"); feedback.getStyleClass().add("fb-high");
            feedback.setText(String.format(Locale.ROOT, "%.1f g over the target - too much; start again with a fresh container.", g - target));
        } else {
            gauge.getStyleClass().add("gauge-ok"); feedback.getStyleClass().add("fb-ok");
            feedback.setText(String.format(Locale.ROOT, "Within range (%+.1f g from the target).", g - target));
        }
    }

    private static Double parse(String text) {
        if (text == null || text.isBlank()) return null;
        try { return Double.parseDouble(text.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    // ---------------------------------------------------------------- dosing

    private void confirmDose() {
        TruckMarking t = current;
        RequiredDose r = active();
        if (t == null || r == null) throw new IllegalArgumentException("Choose a truck and a compartment first.");
        String code = jerrican.getText().trim();
        if (measured.getText().isBlank()) throw new IllegalArgumentException("Read the dose from the scale first");
        double grams;
        try { grams = Double.parseDouble(measured.getText().trim().replace(',', '.')); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("The measured dose must be a number of grams"); }
        if (code.isEmpty()) throw new IllegalArgumentException("Scan the jerrican barcode");
        String source = measured.getText().trim().equals(readFromScale) ? "SCALE" : "MANUAL";
        if (!Ui.confirm(String.format(Locale.ROOT, "Record a dose of %.1f g (target %.1f g) for compartment %s of truck %s (%s, %s L) from jerrican %s?%s",
                grams, r.requiredG(), r.compartment(), t.getTruckNumber(), r.fuelType(), Ui.fmt(r.litres()), code,
                "MANUAL".equals(source) ? "\n\nThis number was typed in, not read from the scale, and will be marked as manual." : ""))) return;
        byte[] sig = Ui.signaturePad().capture(Session.get().user().fullName() + " - sign to confirm dosing");
        if (sig == null) return;
        double recorded = service.dose(Session.get().user(), t.getUuid(), r.compartment(), code, grams, source, sig);
        String uuid = t.getUuid().toString();
        refresh();
        TruckMarking after = queue.getItems().stream().filter(x -> x.getUuid().toString().equals(uuid)).findFirst().orElse(null);
        if (after == null) {
            Ui.info(String.format(Locale.ROOT, "Dosed %.1f g. Hand the container to the dispense operator.", recorded));
        } else {
            queue.getSelectionModel().select(after);                     // the next compartment is chosen for you
            Ui.info(String.format(Locale.ROOT, "Dosed %.1f g for compartment %s. Still to dose: %s.", recorded, r.compartment(),
                    after.remainingDoses().stream().map(RequiredDose::compartment).collect(java.util.stream.Collectors.joining(", "))));
        }
    }

    @Override
    void refresh() {
        rumNames = new HashMap<>(); rumColors = new HashMap<>();
        for (RumType r : Tx.<List<RumType>>call(c -> new MasterDataDao().rumTypes(c))) { rumNames.put(r.code(), r.name()); rumColors.put(r.code(), r.color()); }
        var shift = Session.get().shift();
        List<TruckMarking> waiting = shift == null ? List.of() : service.byShift(shift.id(), MarkingStatus.REGISTERED);
        String keep = current == null ? null : current.getUuid().toString();
        queue.getItems().setAll(waiting);
        if (keep != null) waiting.stream().filter(t -> t.getUuid().toString().equals(keep)).findFirst().ifPresent(queue.getSelectionModel()::select);
        if (queue.getSelectionModel().getSelectedItem() == null) truckChosen(null);
    }
}
