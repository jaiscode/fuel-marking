package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.MasterDataDao;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.service.ContainerService;
import org.jai.fuelmarking.session.Session;

import java.util.List;

/**
 * Warehouse: receive EMPTY jerricans that head office has registered, receive IBCs/drums, and refill jerricans from them.
 * Refilling needs the seals: one for a plastic jerrican, two for a metal one. Each operation is its own screen: "Receive jerrican" and
 * "Refill jerrican" are in the Jerricans menu, "Receive IBC / drum" is a menu item of its own.
 */
class WarehouseTab extends RefreshableTab {
    private final ContainerService service = new ContainerService();
    private final ComboBox<RumType> jerricanRum = new ComboBox<>();
    private final ComboBox<RumType> bulkRum = new ComboBox<>();
    private final ComboBox<ContainerType> bulkType = new ComboBox<>();

    /** Which operation this screen is. */
    enum Mode { RECEIVE_JERRICAN, RECEIVE_BULK, REFILL }

    WarehouseTab(Mode mode) {
        super(mode == Mode.RECEIVE_JERRICAN ? "Receive jerrican" : mode == Mode.REFILL ? "Refill jerrican" : "Receive IBC / drum");
        if (mode != Mode.RECEIVE_BULK) inGroup("Jerricans");
        jerricanRum.setPromptText("Select rum type");
        bulkRum.setPromptText("Select rum type");
        bulkType.setPromptText("Select container type");
        RumSwatch.style(jerricanRum);
        RumSwatch.style(bulkRum);

        // ---- 1. receive an empty jerrican, checked against head office's list
        TextField jerricanId = new TextField();
        jerricanId.setPromptText("Scan or type the jerrican id");
        Label registered = new Label("Head office registers every jerrican (id, container type, capacity, rum type) in the web app.");
        registered.setWrapText(true);
        Runnable lookup = () -> {
            var reg = service.registeredJerrican(jerricanId.getText());
            if (jerricanId.getText().isBlank()) return;
            if (reg.isEmpty()) { registered.setText("Not registered at head office - it cannot be received."); registered.setGraphic(null); return; }
            RegisteredJerrican r = reg.get();
            registered.setText(r.type().name() + ", holds " + Ui.kg(r.capacityG()) + " kg, " + r.seals()
                    + (r.seals() == 1 ? " seal" : " seals") + ", registered for rum type " + r.rumTypeCode() + ".");
            jericanRumSelect(r.rumTypeCode());
            registered.setGraphic(RumSwatch.square(colorOf(r.rumTypeCode())));
        };
        jerricanId.setOnAction(e -> Ui.attempt(lookup));
        jerricanId.focusedProperty().addListener((o, was, now) -> { if (!now) Ui.attempt(lookup); });
        Button receiveJerrican = Ui.primary(new Button("Receive empty jerrican"));
        receiveJerrican.setOnAction(e -> Ui.attempt(() -> {
            Session s = Session.get();
            service.receiveEmptyJerrican(s.user(), s.site(), jerricanId.getText(), jerricanRum.getValue() == null ? null : jerricanRum.getValue().code());
            jerricanId.clear();
            registered.setText("Received. It stays EMPTY until it is refilled and sealed.");
            Ui.info("Jerrican received.");
        }));
        var j = Ui.form();
        Ui.row(j, 0, "Jerrican id", jerricanId);
        Ui.row(j, 1, "Rum type", jerricanRum);
        j.add(registered, 1, 2);
        j.add(receiveJerrican, 1, 3);

        // ---- 2. receive an IBC or drum
        TextField bulkBarcode = new TextField(), bulkCapacity = new TextField(), bulkVolume = new TextField(),
                bulkTop = new TextField(), bulkBottom = new TextField();
        bulkType.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            // the usual size of the type, when it has one (IBCs and drums vary, so they start empty)
            bulkCapacity.setText(t == null || t.defaultCapacityG() == null ? "" : Ui.kg(t.defaultCapacityG()));
        });
        Button receiveBulk = Ui.primary(new Button("Receive IBC / drum"));
        receiveBulk.setOnAction(e -> Ui.attempt(() -> {
            Session s = Session.get();
            service.receiveBulk(s.user(), s.site(), bulkBarcode.getText(), bulkType.getValue() == null ? null : bulkType.getValue().code(), Ui.gramsFromKg(bulkCapacity.getText(), "Capacity"),
                    Ui.gramsFromKg(bulkVolume.getText(), "Volume"), bulkRum.getValue() == null ? null : bulkRum.getValue().code(),
                    bulkTop.getText(), bulkBottom.getText());
            bulkBarcode.clear(); bulkVolume.clear(); bulkTop.clear(); bulkBottom.clear();
            Ui.info("Container received.");
        }));
        var b = Ui.form();
        Ui.row(b, 0, "Barcode", bulkBarcode);
        Ui.row(b, 1, "Type", bulkType);
        Ui.row(b, 2, "Capacity (kg)", bulkCapacity);
        Ui.row(b, 3, "Rum type", bulkRum);
        Ui.row(b, 4, "Current volume (kg)", bulkVolume);
        Ui.row(b, 5, "Top seal", bulkTop);
        Ui.row(b, 6, "Bottom seal", bulkBottom);
        b.add(receiveBulk, 1, 7);

        // ---- 3. refill a jerrican from an IBC/drum and seal it
        TextField jerry = new TextField(), bulk = new TextField(), quantity = new TextField(), seal1 = new TextField(), seal2 = new TextField();
        jerry.setPromptText("Scan or type the jerrican id");
        bulk.setPromptText("Scan the IBC/drum barcode");
        seal1.setPromptText("Seal number");
        Label jerryInfo = new Label("Scan the jerrican to see how many seals it needs.");
        jerryInfo.setWrapText(true);
        Runnable describeJerry = () -> {
            if (jerry.getText().isBlank()) return;
            var k = service.find(jerry.getText());
            if (k.isEmpty() || k.get().type().isBulk()) {
                jerryInfo.setText("Unknown jerrican - receive it as an empty jerrican first.");
                jerryInfo.setGraphic(null);
                seal2.setDisable(false);
                return;
            }
            Container c = k.get();
            boolean two = c.type().seals() >= 2;
            seal2.setDisable(!two);
            if (!two) seal2.clear();
            seal2.setPromptText(two ? "Second seal number" : "Not needed - " + c.type().name().toLowerCase() + " has one seal");
            jerryInfo.setText(c.type().name() + ": " + c.type().seals() + (c.type().seals() == 1 ? " seal." : " seals.") + " Holds up to " + Ui.kg(c.capacityMl()) + " kg, rum type "
                    + c.rumTypeCode() + ", currently " + c.status() + ("EMPTY".equals(c.status()) ? "." : " - only an EMPTY jerrican can be refilled."));
            jerryInfo.setGraphic(RumSwatch.square(colorOf(c.rumTypeCode())));
        };
        jerry.setOnAction(e -> Ui.attempt(describeJerry));
        jerry.focusedProperty().addListener((o, was, now) -> { if (!now) Ui.attempt(describeJerry); });
        Button refill = Ui.primary(new Button("Refill and seal"));
        Button tare = new Button("Tare scale");
        tare.setOnAction(e -> Weigh.tare(tare));
        Button read = new Button("Read scale");
        read.setOnAction(e -> Weigh.readStable(read, kg -> quantity.setText(Ui.kg(Weigh.roundGrams(kg)))));
        Label steps = new Label("1. Put the EMPTY jerrican on the scale and press Tare.   "
                + "2. Fill it from the IBC/drum while watching the display.   "
                + "3. Press Read scale - the net weight is the rum filled.   "
                + "4. Scan both barcodes, enter the seal number(s) you fitted and press Refill and seal.");
        steps.setWrapText(true);
        refill.setOnAction(e -> Ui.attempt(() -> {
            service.refill(Session.get().user(), jerry.getText(), bulk.getText(), Ui.gramsFromKg(quantity.getText(), "Quantity"),
                    seal1.getText(), seal2.getText());
            jerry.clear(); bulk.clear(); quantity.clear(); seal1.clear(); seal2.clear(); seal2.setDisable(false); jerryInfo.setGraphic(null);
            jerryInfo.setText("Scan the jerrican to see how many seals it needs.");
            Ui.info("Jerrican refilled and sealed.");
        }));
        var r = Ui.form();
        Ui.row(r, 0, "Jerrican id", jerry);
        r.add(jerryInfo, 1, 1);
        Ui.row(r, 2, "From IBC/drum barcode", bulk);
        Ui.row(r, 3, "Rum filled (kg)", quantity);
        Ui.row(r, 4, "Seal", seal1);
        Ui.row(r, 5, "Second seal", seal2);
        r.add(new HBox(10, tare, read, refill), 1, 6);

        // each operation is its own screen: only its form is built into the page
        var page = new VBox(14);
        page.setPadding(new Insets(20));
        switch (mode) {
            case RECEIVE_JERRICAN -> page.getChildren().addAll(Ui.header("Receive jerrican", "Scan the jerrican that head office registered. It is accepted only if it is on head office's list, and it stays EMPTY until it is refilled and sealed."), j);
            case RECEIVE_BULK -> page.getChildren().addAll(Ui.header("Receive IBC / drum", "Bulk containers hold the rum the jerricans are refilled from."), b);
            case REFILL -> page.getChildren().addAll(Ui.header("Refill jerrican", "Fill an empty jerrican from an IBC or drum and seal it."), steps, new ScaleDisplay(), r);
        }
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        setContent(scroll);
        loadRumTypes();
    }

    private static ToggleButton tile(ToggleGroup group, Node icon, String text, String tip) {
        ToggleButton t = new ToggleButton(text, icon);
        t.setToggleGroup(group);
        t.getStyleClass().add("tile");
        t.setTooltip(new Tooltip(tip));
        return t;
    }

    /** A titled form for one operation. */
    private static VBox panel(String title, String hint, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("tile-title");
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.getChildren().add(heading);
        if (hint != null) {
            Label h = new Label(hint);
            h.setWrapText(true);
            h.getStyleClass().add("hint");
            box.getChildren().add(h);
        }
        box.getChildren().addAll(content);
        return box;
    }

    private static void show(StackPane panels, Node chosen) {
        for (Node n : panels.getChildren()) { n.setVisible(n == chosen); n.setManaged(n == chosen); }
    }

    private String colorOf(String rumTypeCode) {
        return jerricanRum.getItems().stream().filter(t -> t.code().equals(rumTypeCode)).map(RumType::color).findFirst().orElse(null);
    }

    private void jericanRumSelect(String code) {
        jerricanRum.getItems().stream().filter(t -> t.code().equals(code)).findFirst().ifPresent(jerricanRum.getSelectionModel()::select);
    }

    private void loadRumTypes() {
        ContainerType chosenType = bulkType.getValue();
        bulkType.getItems().setAll(Tx.<List<ContainerType>>call(c -> new MasterDataDao().containerTypes(c, "BULK")));
        if (chosenType != null) bulkType.getItems().stream().filter(t -> t.code().equals(chosenType.code())).findFirst().ifPresent(bulkType.getSelectionModel()::select);
        else if (bulkType.getItems().size() == 1) bulkType.getSelectionModel().selectFirst();
        List<RumType> types = Tx.call(c -> new MasterDataDao().rumTypes(c));
        RumType a = jerricanRum.getValue(), b = bulkRum.getValue();
        jerricanRum.getItems().setAll(types);
        bulkRum.getItems().setAll(types);
        if (a != null) jericanRumSelect(a.code());
        if (b != null) bulkRum.getItems().stream().filter(t -> t.code().equals(b.code())).findFirst().ifPresent(bulkRum.getSelectionModel()::select);
        if (types.size() == 1) { bulkRum.getSelectionModel().selectFirst(); }
    }

    @Override
    void refresh() { loadRumTypes(); }
}
