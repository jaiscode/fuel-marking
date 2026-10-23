package org.jai.fuelmarking.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.config.YamlConfigurationLoader.FuelMarking;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.*;
import org.jai.fuelmarking.model.Records.FuelType;
import org.jai.fuelmarking.model.Records.Omc;
import org.jai.fuelmarking.model.Records.RumType;
import org.jai.fuelmarking.service.MarkingService;
import org.jai.fuelmarking.session.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers a truck by its compartments. Each compartment says which fuel it carries - a truck can carry diesel in one and petrol in another -
 * and each fuel type is marked with one particular rum type (set at head office), so the rum the truck needs follows from its fuels.
 */
class RegisterTab extends RefreshableTab {
    private final MarkingService service = new MarkingService();
    private final TextField truck = new TextField();
    private final TextField trailer = new TextField();
    private final ComboBox<Omc> omc = new ComboBox<>();
    private final TextField volume = new TextField();            // the total loaded; follows the compartments
    private final ObservableList<CompartmentDetail> compartments = FXCollections.observableArrayList();
    private final TableView<CompartmentDetail> compartmentTable = new TableView<>(compartments);
    private final TextField compartmentCapacity = new TextField();
    private final TextField compartmentLoaded = new TextField();
    private final ComboBox<FuelType> compartmentFuel = new ComboBox<>();
    private final Label fuelRumHint = new Label();
    private final Label compartmentTotal = new Label();
    private final Label doseValue = new Label("-"), doseNote = new Label();
    private final ListView<TruckMarking> pending = new ListView<>();
    private final Button deleteTruck = new Button("Delete this truck");
    private final Label pendingHint = new Label("Select a truck to delete it. A truck can be deleted until its first dose.");
    private Map<String, String> rumColors = Map.of();
    private Map<String, String> rumNames = Map.of();

    RegisterTab() {
        super("Register truck");
        trailer.setPromptText("Leave blank if none");
        compartmentCapacity.setPromptText("Capacity (L)");
        compartmentLoaded.setPromptText("Loaded (L) - blank if full");
        compartmentFuel.setPromptText("Fuel in this compartment");   // no default: the supervisor says what each compartment really carries
        compartmentFuel.setMaxWidth(Double.MAX_VALUE);
        fuelRumHint.getStyleClass().add("hint");
        compartmentFuel.valueProperty().addListener((o, a, f) -> fuelRumHint.setText(f == null ? "" : f.rumTypeCode() == null
                ? f.name() + " has no rum type yet - head office sets it in the web app."
                : f.name() + " is marked with " + rumNames.getOrDefault(f.rumTypeCode(), f.rumTypeCode()) + "."));
        Button add = new Button("Add compartment");
        add.setOnAction(e -> Ui.attempt(this::addCompartment));
        compartmentLoaded.setOnAction(e -> add.fire());
        Button remove = new Button("Remove selected");
        remove.disableProperty().bind(compartmentTable.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeSelectedCompartment());
        compartmentTable.getColumns().add(Ui.col("No.", CompartmentDetail::getCompartmentNumber));
        compartmentTable.getColumns().add(Ui.col("Fuel", d -> d.getFuelType() == null ? "" : d.getFuelType()));
        var rum = Ui.col("Rum type", (CompartmentDetail d) -> d.getRumType() == null ? "" : d.getRumType());
        RumSwatch.column(rum, CompartmentDetail::getRumType, () -> rumColors);
        rum.setPrefWidth(190);
        compartmentTable.getColumns().add(rum);
        compartmentTable.getColumns().add(Ui.col("Capacity (L)", d -> Ui.fmt(d.getCapacity())));
        compartmentTable.getColumns().add(Ui.col("Loaded (L)", d -> Ui.fmt(d.getActualVolume())));
        compartmentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        compartmentTable.setPlaceholder(new Label("No compartments yet - enter a capacity, the loaded volume and the fuel, then press Add"));
        compartmentTable.setPrefHeight(160);
        compartmentTable.setMinHeight(120);
        HBox entry = new HBox(8, compartmentCapacity, compartmentLoaded);
        HBox.setHgrow(compartmentCapacity, Priority.ALWAYS);
        HBox.setHgrow(compartmentLoaded, Priority.ALWAYS);
        HBox fuelRow = new HBox(8, compartmentFuel, add);
        HBox.setHgrow(compartmentFuel, Priority.ALWAYS);
        compartments.addListener((javafx.collections.ListChangeListener<CompartmentDetail>) c -> compartmentsChanged());
        volume.setEditable(false);
        volume.setFocusTraversable(false);
        volume.setPromptText("Follows the compartments");
        compartmentsChanged();
        Button register = Ui.primary(new Button("Review & sign"));
        register.setOnAction(e -> Ui.attempt(this::register));
        omc.setMaxWidth(Double.MAX_VALUE);

        // ---- left: which truck, whose fuel
        VBox truckCard = Ui.card(Ui.section("Truck and order"), Ui.field("Truck number", truck), Ui.field("Trailer / wagon number", trailer),
                Ui.field("Oil marketing company", omc));
        truckCard.setPrefWidth(380);
        truckCard.setMinWidth(320);

        // ---- right: the compartments, each with its fuel
        compartmentTotal.setWrapText(true);
        HBox below = new HBox(12, remove, compartmentTotal);
        below.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("Add each compartment with its capacity, what is loaded in it (blank = full) and the fuel it carries. A truck can carry different fuels; each fuel is marked with its own rum type.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        VBox loadCard = Ui.card(Ui.section("Load"), Ui.field("Total loaded (L)", volume), Ui.field("Compartment", entry), Ui.field("Fuel", fuelRow), fuelRumHint, hint,
                compartmentTable, below);
        HBox.setHgrow(loadCard, Priority.ALWAYS);
        HBox cards = new HBox(14, truckCard, loadCard);

        // ---- bottom: what it means in rum, one dose for each rum type, and the sign button
        doseValue.getStyleClass().add("big-number");
        doseNote.getStyleClass().add("hint");
        doseNote.setWrapText(true);
        Label doseCaption = new Label("RUM TO DOSE");
        doseCaption.getStyleClass().add("kpi-label");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(16, new VBox(2, doseCaption, doseValue, doseNote), grow, register);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("card");
        summaryChanged();

        // ---- registered trucks that have not been dosed yet: a wrong one can be deleted until its first dose
        pending.getStyleClass().add("jar-list");
        pending.setCellFactory(lv -> new PendingCell());
        pending.setPlaceholder(new Label("No registered trucks waiting"));
        pending.setPrefHeight(190);
        pending.setMinHeight(110);
        pendingHint.getStyleClass().add("hint");
        pendingHint.setWrapText(true);
        deleteTruck.getStyleClass().add("danger");
        deleteTruck.setDisable(true);
        deleteTruck.setOnAction(e -> Ui.attempt(this::deleteSelected));
        pending.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> pendingChosen(t));
        Region pendingGrow = new Region();
        HBox.setHgrow(pendingGrow, Priority.ALWAYS);
        HBox pendingBar = new HBox(12, pendingHint, pendingGrow, deleteTruck);
        pendingBar.setAlignment(Pos.CENTER_LEFT);
        VBox pendingCard = Ui.card(Ui.section("Registered, waiting to be dosed"), pending, pendingBar);

        setContent(new ScrollPane(Ui.page(Ui.header("Register truck", "Record the truck, its order and how it is loaded. Each compartment has its own fuel, and the rum dose follows from the fuels."), cards, bar, pendingCard)) {{ setFitToWidth(true); }});
    }

    /** A registered truck waiting for its rum: which truck, how it is loaded, and whether dosing has started. */
    private final class PendingCell extends ListCell<TruckMarking> {
        @Override protected void updateItem(TruckMarking t, boolean empty) {
            super.updateItem(t, empty);
            if (empty || t == null) { setGraphic(null); setText(null); return; }
            Label number = new Label(t.getTruckNumber() + (t.getTrailerNumber() == null ? "" : " + " + t.getTrailerNumber()));
            number.getStyleClass().addAll("jar-title", "mono");
            StringBuilder load = new StringBuilder();
            for (CompartmentDetail d : t.getCompartments()) {
                if (load.length() > 0) load.append("   ");
                load.append(d.getCompartmentNumber()).append(" ").append(Ui.fmt(d.getActualVolume())).append(" L ").append(d.getFuelType() == null ? "" : d.getFuelType());
            }
            Label sub = new Label(t.getOilMarketingCompany() + "  -  " + t.getOrderNumber() + (load.length() == 0 ? "" : "  -  " + load));
            sub.getStyleClass().add("hint");
            boolean started = !t.getDoses().isEmpty();
            Label pill = new Label(started ? "Dosing started (" + t.getDoses().size() + " of " + t.getRequiredDoses().size() + ")" : "Not dosed yet");
            pill.getStyleClass().addAll("pill", started ? "pill-sealed" : "pill-opened");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(12, new VBox(1, number, sub), grow, pill);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new javafx.geometry.Insets(8, 10, 8, 10));
            row.getStyleClass().add("jar-card");
            setGraphic(row);
            setText(null);
        }
    }

    private void pendingChosen(TruckMarking t) {
        boolean started = t != null && !t.getDoses().isEmpty();
        deleteTruck.setDisable(t == null || started);
        pendingHint.setText(t == null ? "Select a truck to delete it. A truck can be deleted until its first dose."
                : started ? "Dosing has started on " + t.getTruckNumber() + " - it can no longer be deleted."
                : "Registered by mistake? Delete " + t.getTruckNumber() + " - it stays on record as deleted.");
    }

    private void refreshPending() {
        var shift = Session.get().shift();
        TruckMarking keep = pending.getSelectionModel().getSelectedItem();
        pending.getItems().setAll(shift == null ? List.<TruckMarking>of() : service.byShift(shift.id(), MarkingStatus.REGISTERED));
        if (keep != null) pending.getItems().stream().filter(x -> x.getUuid().equals(keep.getUuid())).findFirst().ifPresent(pending.getSelectionModel()::select);
        pendingChosen(pending.getSelectionModel().getSelectedItem());
    }

    private void deleteSelected() {
        Session s = Session.get();
        TruckMarking t = pending.getSelectionModel().getSelectedItem();
        if (t == null) throw new IllegalArgumentException("Select the truck to delete first");
        if (!Ui.confirm("Delete the registration of truck " + t.getTruckNumber() + " (" + t.getOrderNumber() + ")?\n\nIt will no longer appear anywhere and cannot be dosed. "
                + "It stays on record as deleted.")) return;
        service.delete(s.user(), s.site(), s.shift(), t.getUuid());
        refreshPending();
        Ui.info("Deleted " + t.getTruckNumber() + ".");
    }

    private static double number(String text, String what) {
        try { return Double.parseDouble(text.trim().replace(",", "")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be a number"); }
    }

    private void addCompartment() {
        double capacity = number(compartmentCapacity.getText(), "Compartment capacity");
        if (capacity <= 0) throw new IllegalArgumentException("Compartment capacity must be more than zero");
        double loaded = compartmentLoaded.getText().isBlank() ? capacity : number(compartmentLoaded.getText(), "Loaded volume");
        if (loaded < 0) throw new IllegalArgumentException("Loaded volume cannot be negative");
        if (loaded > capacity) throw new IllegalArgumentException("Loaded volume (" + Ui.fmt(loaded) + " L) is more than the capacity ("
                + Ui.fmt(capacity) + " L)");
        FuelType fuel = compartmentFuel.getValue();
        if (fuel == null) throw new IllegalArgumentException("Select the fuel in this compartment");
        if (fuel.rumTypeCode() == null || fuel.rumTypeCode().isBlank())
            throw new IllegalArgumentException(fuel.name() + " has no rum type yet - head office sets it in the web app");
        compartments.add(new CompartmentDetail("C" + (compartments.size() + 1), capacity, loaded, fuel.code(), fuel.rumTypeCode()));
        compartmentCapacity.clear();
        compartmentLoaded.clear();
        compartmentCapacity.requestFocus();
    }

    private void removeSelectedCompartment() {
        int i = compartmentTable.getSelectionModel().getSelectedIndex();
        if (i < 0) return;
        compartments.remove(i);
        for (int n = 0; n < compartments.size(); n++) {          // keep the numbers C1, C2 ... without gaps
            CompartmentDetail d = compartments.get(n);
            compartments.set(n, new CompartmentDetail("C" + (n + 1), d.getCapacity(), d.getActualVolume(), d.getFuelType(), d.getRumType()));
        }
    }

    /** The total follows the compartments. */
    private void compartmentsChanged() {
        double total = compartments.stream().mapToDouble(CompartmentDetail::getActualVolume).sum();
        volume.setText(compartments.isEmpty() ? "" : String.format(java.util.Locale.ROOT, "%.1f", total).replaceAll("\\.0$", ""));
        long fuels = compartments.stream().map(CompartmentDetail::getFuelType).distinct().count();
        compartmentTotal.setText(compartments.isEmpty() ? "" : compartments.size() + " compartment(s), " + Ui.fmt(total) + " L loaded"
                + (fuels > 1 ? ", " + fuels + " fuels" : ""));
        compartmentTotal.getStyleClass().removeAll("check-ok", "check-todo");
        if (!compartments.isEmpty()) compartmentTotal.getStyleClass().add("check-ok");
        summaryChanged();
    }

    /** Rum each rum type has to supply (grams), from the litres in the compartments marked with it. */
    private Map<String, Double> doses() {
        double per1000 = FuelMarking.getRumDosagePer1000L();
        Map<String, Double> out = new LinkedHashMap<>();
        for (CompartmentDetail d : compartments) if (d.getRumType() != null) out.merge(d.getRumType(), d.getActualVolume() / 1000.0 * per1000, Double::sum);
        return out;
    }

    /** The rum to dose, in total and for each rum type, shown as soon as there are compartments. */
    private void summaryChanged() {
        Map<String, Double> doses = doses();
        if (doses.isEmpty()) {
            doseValue.setText("-");
            doseNote.setText("Add a compartment to see the dose.");
            return;
        }
        double tol = FuelMarking.getDoseTolerancePercent();
        double total = doses.values().stream().mapToDouble(Double::doubleValue).sum();
        doseValue.setText(Ui.fmt(total) + " g");
        StringBuilder sb = new StringBuilder();
        for (var e : doses.entrySet()) {
            if (sb.length() > 0) sb.append("   +   ");
            sb.append(rumNames.getOrDefault(e.getKey(), e.getKey())).append(" ").append(Ui.fmt(e.getValue())).append(" g");
        }
        doseNote.setText(sb + "   -   one dose per rum type, each within " + Ui.fmt(tol) + "% (" + FuelMarking.getRumDosagePer1000L() + " g per 1,000 L)");
    }

    private void register() {
        Session s = Session.get();
        if (omc.getValue() == null) throw new IllegalArgumentException("Select the oil marketing company");
        if (compartments.isEmpty()) throw new IllegalArgumentException("Add at least one compartment");
        List<CompartmentDetail> comps = new ArrayList<>(compartments);
        StringBuilder lines = new StringBuilder();
        for (CompartmentDetail d : comps)
            lines.append("\n  ").append(d.getCompartmentNumber()).append(": ").append(Ui.fmt(d.getActualVolume())).append(" L ").append(d.getFuelType())
                    .append(" (").append(rumNames.getOrDefault(d.getRumType(), d.getRumType())).append(")");
        StringBuilder rum = new StringBuilder();
        for (var e : doses().entrySet()) rum.append("\n  ").append(rumNames.getOrDefault(e.getKey(), e.getKey())).append(": ").append(Ui.fmt(e.getValue())).append(" g");
        double declared = comps.stream().mapToDouble(CompartmentDetail::getActualVolume).sum();
        if (!Ui.confirm("Registration summary\n\nTruck: " + truck.getText().trim().toUpperCase()
                + (trailer.getText().isBlank() ? "" : "\nTrailer / wagon: " + trailer.getText().trim().toUpperCase()) + "\nOMC: " + omc.getValue().name()
                + "\nDeclared volume: " + Ui.fmt(declared) + " L\nCompartments:" + lines + "\nRum to dose:" + rum + "\n\nSign to register?")) return;
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign the registration");
        if (sig == null) return;
        TruckMarking t = service.register(s.user(), s.site(), s.shift(), omc.getValue(), truck.getText(), trailer.getText(), comps, sig);
        truck.clear(); trailer.clear(); compartments.clear(); compartmentCapacity.clear(); compartmentLoaded.clear(); compartmentFuel.getSelectionModel().clearSelection();
        refreshPending();
        Ui.info("Registered. Order number: " + t.getOrderNumber());
    }

    @Override
    void refresh() {
        omc.getItems().setAll(Tx.<List<Omc>>call(c -> new MasterDataDao().omcs(c)));
        FuelType f = compartmentFuel.getValue();
        var rums = Tx.<List<RumType>>call(c -> new MasterDataDao().rumTypes(c));
        rumColors = new LinkedHashMap<>(); rumNames = new LinkedHashMap<>();
        for (RumType r : rums) { rumColors.put(r.code(), r.color()); rumNames.put(r.code(), r.name()); }
        compartmentFuel.getItems().setAll(Tx.<List<FuelType>>call(c -> new MasterDataDao().fuelTypes(c)));
        if (f != null) compartmentFuel.getItems().stream().filter(x -> x.code().equals(f.code())).findFirst().ifPresent(compartmentFuel.getSelectionModel()::select);
        summaryChanged();
        refreshPending();
    }
}
