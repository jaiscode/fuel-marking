package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.service.MarkingService;
import org.jai.fuelmarking.session.Session;

import java.util.UUID;
import java.util.function.Consumer;

/** Shared layout for the dose / dispense / verify screens: a queue of trucks plus one action. */
abstract class MarkingStepTab extends RefreshableTab {
    protected final MarkingService service = new MarkingService();
    protected final TableView<TruckMarking> queue = new TableView<>();
    private final MarkingStatus waitingFor;
    protected final javafx.scene.layout.VBox actionBox = new javafx.scene.layout.VBox(8);

    MarkingStepTab(String title, MarkingStatus waitingFor, String buttonText) {
        super(title);
        this.waitingFor = waitingFor;
        queue.getColumns().add(Ui.col("Order", TruckMarking::getOrderNumber));
        queue.getColumns().add(Ui.col("Truck", TruckMarking::getTruckNumber));
        queue.getColumns().add(Ui.col("Trailer / wagon", t -> t.getTrailerNumber() == null ? "" : t.getTrailerNumber()));
        queue.getColumns().add(Ui.col("OMC", TruckMarking::getOilMarketingCompany));
        queue.getColumns().add(Ui.col("Fuel", t -> t.getFuelType()));
        queue.getColumns().add(Ui.col("Volume (L)", t -> Ui.fmt(t.getDeclaredVolume())));
        queue.getColumns().add(Ui.col("Rum to dose (g)", t -> Ui.fmt(t.calculateRequiredRum())));
        queue.setPlaceholder(new Label("Nothing waiting"));
        queue.setPrefHeight(250);
        queue.setMinHeight(150);
        Button action = Ui.primary(new Button(buttonText));
        action.setOnAction(e -> {
            TruckMarking t = queue.getSelectionModel().getSelectedItem();
            if (t == null) { Ui.error("Select a truck first."); return; }
            Ui.attempt(() -> perform(t));
        });
        actionBox.getChildren().add(action);
        actionBox.getStyleClass().add("card");
        String what = switch (waitingFor) {
            case REGISTERED -> "Trucks registered and waiting for their rum dose. Select one, then dose it.";
            case DOSED -> "Trucks that have been dosed and are waiting for the rum to be put in.";
            default -> "Trucks that have had their rum put in and are waiting to be checked and closed.";
        };
        setContent(Ui.page(Ui.header(title, what), Ui.section("Waiting"), queue, actionBox));
    }

    /** Signs with the pad and calls the service for the selected truck. */
    protected abstract void perform(TruckMarking t);

    protected byte[] sign(String what) {
        return Ui.signaturePad().capture(Session.get().user().fullName() + " - " + what);
    }

    protected void done(String message) {
        refresh();
        Ui.info(message);
    }

    @Override
    void refresh() {
        var shift = Session.get().shift();
        queue.getItems().setAll(shift == null ? java.util.List.of() : service.byShift(shift.id(), waitingFor));
    }
}
