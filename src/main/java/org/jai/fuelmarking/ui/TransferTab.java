package org.jai.fuelmarking.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.service.ContainerService;
import org.jai.fuelmarking.service.TransferService;
import org.jai.fuelmarking.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatch containers to another site (warehouse manager) and receive incoming transfers (supervisor / manager).
 * Containers are not weighed: the list of scanned containers and the signature are the record.
 */
class TransferTab extends RefreshableTab {
    /** An incoming container that can be ticked off as received. */
    static final class Incoming {
        final String barcode;
        final String type;
        final SimpleBooleanProperty receive = new SimpleBooleanProperty(true);
        Incoming(String barcode, String type) { this.barcode = barcode; this.type = type; }
    }

    private final TransferService service = new TransferService();
    private boolean returning;          // a supervisor sends empties back to a warehouse; a warehouse manager sends sealed containers out
    private final ContainerService containers = new ContainerService();
    private final ComboBox<Site> destination = new ComboBox<>();
    private final TextField barcode = new TextField();
    private final TableView<Container> outgoing = new TableView<>();
    private final ListView<Transfer> incoming = new ListView<>();
    private final Map<UUID, String> incomingLabels = new java.util.HashMap<>();
    private final TableView<Incoming> incomingItems = new TableView<>();

    /** What the screen offers: sending, receiving, or both on one screen (the warehouse manager's Transfers). */
    enum Mode { SEND, RECEIVE, BOTH }

    TransferTab() { this(Mode.BOTH); }

    /** The warehouse manager's "Receive jerrican" is receiving a jerrican from head office, so what arrives by transfer is "Receive returns" there. */
    private static String titleFor(Mode mode) {
        boolean manager = Session.get().user().role() == Role.WAREHOUSE_MANAGER;
        return mode == Mode.SEND ? "Transfer jerrican" : mode == Mode.RECEIVE ? (manager ? "Receive returns" : "Receive jerrican") : "Transfers";
    }

    /** The supervisor has two screens under the Jerricans menu: Transfer jerrican (send empties back) and Receive jerrican (take in what arrives). */
    TransferTab(Mode mode) {
        super(titleFor(mode));
        if (mode != Mode.BOTH) inGroup("Jerricans");
        Role role = Session.get().user().role();
        returning = role == Role.SUPERVISOR;
        String intro = switch (mode) {
            case SEND -> role == Role.WAREHOUSE_MANAGER ? "Send sealed jerricans (or IBCs and drums) to another site."
                    : "Send finished (empty) jerricans back to a warehouse for a refill.";
            case RECEIVE -> role == Role.WAREHOUSE_MANAGER ? "Take in what was sent to this warehouse - finished jerricans coming back, for example: select a transfer and tick what arrived."
                    : "Take in the containers that were sent to this site: select a transfer and tick what arrived.";
            case BOTH -> role == Role.WAREHOUSE_MANAGER ? "Send sealed containers to another site, and receive the ones sent to you."
                    : "Send finished (empty) jerricans back to a warehouse for a refill, and receive the containers sent to this site.";
        };
        var page = Ui.page(Ui.header(titleFor(mode), intro));
        if (mode != Mode.RECEIVE && (role == Role.WAREHOUSE_MANAGER || returning)) {
            barcode.setPromptText(returning ? "Scan empty jerrican" : "Scan container");
            barcode.setOnAction(e -> Ui.attempt(this::add));
            Button add = new Button("Add to transfer");
            add.setOnAction(e -> Ui.attempt(this::add));
            Button remove = new Button("Remove selected");
            remove.disableProperty().bind(outgoing.getSelectionModel().selectedItemProperty().isNull());
            remove.setOnAction(e -> outgoing.getItems().remove(outgoing.getSelectionModel().getSelectedItem()));
            outgoing.getColumns().add(Ui.col("Container", Container::barcode));
            outgoing.getColumns().add(Ui.col("Type", k -> k.type().name()));
            outgoing.getColumns().add(Ui.col("Rum type", k -> k.rumTypeCode() == null ? "" : k.rumTypeCode()));
            outgoing.getColumns().add(Ui.col("Rum (kg)", k -> Ui.kg(k.currentVolumeMl())));
            outgoing.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            outgoing.setPlaceholder(new Label(returning ? "Scan the empty jerricans that are going back" : "Scan the sealed containers that are going"));
            outgoing.setPrefHeight(180);
            Button dispatch = Ui.primary(new Button("Dispatch"));
            dispatch.setOnAction(e -> Ui.attempt(this::dispatch));
            destination.setPromptText(returning ? "Warehouse" : "Destination site");
            page.getChildren().addAll(Ui.section(returning ? "Send empty jerricans back to a warehouse" : "Dispatch to site"), new HBox(10, destination, barcode, add), outgoing,
                    new HBox(10, remove, dispatch));
            if (mode == Mode.BOTH) page.getChildren().add(new Separator());
        }
        incoming.setPrefHeight(120);
        incoming.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Transfer t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : incomingLabels.getOrDefault(t.id(), "Transfer"));
            }
        });
        incoming.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> showItems(t));
        incomingItems.setEditable(true);
        TableColumn<Incoming, Boolean> tick = new TableColumn<>("Received");
        tick.setCellValueFactory(cd -> cd.getValue().receive);
        tick.setCellFactory(CheckBoxTableCell.forTableColumn(tick));
        tick.setEditable(true);
        tick.setPrefWidth(110);
        incomingItems.getColumns().add(tick);
        incomingItems.getColumns().add(Ui.col("Container", (Incoming r) -> r.barcode));
        incomingItems.getColumns().add(Ui.col("Type", (Incoming r) -> r.type));
        incomingItems.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        incomingItems.setPrefHeight(180);
        Button receive = Ui.primary(new Button("Receive ticked containers"));
        receive.setOnAction(e -> Ui.attempt(this::receive));
        Label inHint = new Label("Select a transfer, then tick the containers that arrived.");
        inHint.getStyleClass().add("hint");
        if (mode != Mode.SEND) page.getChildren().addAll(Ui.section("Incoming transfers for this site"), inHint, incoming, incomingItems, receive);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        setContent(scroll);
    }

    private void add() {
        String code = barcode.getText().trim();
        if (code.isEmpty()) throw new IllegalArgumentException("Scan a container barcode");
        if (outgoing.getItems().stream().anyMatch(k -> k.barcode().equals(code))) throw new IllegalArgumentException(code + " is already on the list");
        Container k = containers.find(code).orElseThrow(() -> new IllegalArgumentException("Unknown container " + code));
        if (!k.siteId().equals(Session.get().site().id())) throw new IllegalArgumentException(code + " is not at this site");
        if (returning) {
            if (k.type().isBulk()) throw new IllegalArgumentException(code + " is an IBC/drum - only jerricans are sent back");
            if (!"EMPTY".equals(k.status())) throw new IllegalArgumentException(code + " is " + k.status() + " - declare it empty first (Declare empty screen)");
        } else if (!"SEALED".equals(k.status())) throw new IllegalArgumentException(code + " is " + k.status() + " - only sealed containers can be dispatched");
        outgoing.getItems().add(k);
        barcode.clear();
        barcode.requestFocus();
    }

    private void dispatch() {
        Session s = Session.get();
        if (destination.getValue() == null) throw new IllegalArgumentException("Choose the destination site");
        List<String> codes = outgoing.getItems().stream().map(Container::barcode).toList();
        if (codes.isEmpty()) throw new IllegalArgumentException("Add at least one container");
        if (!Ui.confirm((returning ? "Send " + codes.size() + " empty jerrican(s) back to " : "Dispatch " + codes.size() + " container(s) to ")
                + destination.getValue().name() + "?")) return;
        if (returning) service.returnEmpties(s.user(), s.site(), destination.getValue(), codes);
        else service.dispatch(s.user(), s.site(), destination.getValue(), codes);
        outgoing.getItems().clear();
        Ui.info(returning ? "Empty jerricans sent back." : "Transfer dispatched.");
    }

    private void showItems(Transfer t) {
        incomingItems.getItems().clear();
        if (t == null) return;
        for (TransferItem i : service.items(t.id()))
            if (!i.received()) incomingItems.getItems().add(new Incoming(i.barcode(), containers.find(i.barcode()).map(k -> k.type().name()).orElse("")));
    }

    private void receive() {
        Session s = Session.get();
        Transfer t = incoming.getSelectionModel().getSelectedItem();
        if (t == null) throw new IllegalArgumentException("Select an incoming transfer");
        List<String> ticked = new ArrayList<>();
        for (Incoming i : incomingItems.getItems()) if (i.receive.get()) ticked.add(i.barcode);
        if (ticked.isEmpty()) throw new IllegalArgumentException("Tick at least one container that arrived");
        service.receive(s.user(), t, ticked);
        refresh();
        Ui.info("Received " + ticked.size() + " container(s).");
    }

    @Override
    void refresh() {
        Session s = Session.get();
        destination.getItems().setAll(Tx.<List<Site>>call(c -> new MasterDataDao().sites(c)).stream()
                .filter(x -> !x.id().equals(s.site().id()) && (!returning || "WAREHOUSE".equals(x.siteType()))).toList());
        List<Transfer> pending = service.incoming(s.site().id());
        Map<UUID, String> names = new java.util.HashMap<>();
        for (Site x : Tx.<List<Site>>call(c -> new MasterDataDao().sites(c))) names.put(x.id(), x.name());
        incomingLabels.clear();
        for (Transfer t : pending)
            incomingLabels.put(t.id(), ("RETURN".equals(t.kind()) ? "Empty jerricans back from " : "From ") + names.getOrDefault(t.fromSiteId(), "another site") + "  -  sent "
                    + t.dispatchedAt().toLocalDate() + " " + t.dispatchedAt().toLocalTime().withNano(0).withSecond(0)
                    + "  -  " + service.items(t.id()).size() + " container(s)");
        incoming.getItems().setAll(pending);
        incomingItems.getItems().clear();
    }
}
