package org.jai.fuelmarking.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jai.fuelmarking.model.Role;
import org.jai.fuelmarking.session.Session;

class MainView extends BorderPane {
    private final Label shiftLabel = new Label();
    private TabPane tabPane;
    private final java.util.Map<Tab, ToggleButton> navItems = new java.util.LinkedHashMap<>();
    /** A named menu (e.g. Jerricans) with its sub-items: the header button and the box holding the items. */
    private record NavGroup(String name, Button header, VBox items) {}
    private final java.util.Map<String, NavGroup> navGroups = new java.util.LinkedHashMap<>();
    /** A supervisor who has not started a shift can only start one, and look at reports. */
    private static final java.util.Set<String> BEFORE_SHIFT = java.util.Set.of("Shift", "Reports");

    MainView(Runnable onLogout) {
        Session s = Session.get();
        Label brand = new Label("Fuel Marking");
        brand.getStyleClass().add("brand");
        Label site = new Label(s.site().name());
        site.getStyleClass().add("chip");
        Label name = new Label(s.user().fullName());
        name.getStyleClass().add("user-name");
        Label role = new Label(s.user().role().toString().replace('_', ' ').toLowerCase());
        role.getStyleClass().add("user-role");
        VBox who = new VBox(0, name, role);
        shiftLabel.getStyleClass().add("chip");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button logout = new Button("Sign out");
        logout.getStyleClass().add("ghost");
        logout.setOnAction(e -> { System.err.println("Signed out: Sign out button"); onLogout.run(); });
        HBox top = new HBox(16, brand, site, shiftLabel, spacer, who, logout);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        top.getStyleClass().add("status-bar");
        setTop(top);

        TabPane tabs = new TabPane();
        Runnable shiftChanged = this::updateShiftLabel;
        switch (s.user().role()) {
            case SUPERVISOR -> {
                ReconcileTab[] reconcile = new ReconcileTab[1];
                ShiftTab shiftTab = new ShiftTab(shiftChanged, () -> tabs.getSelectionModel().select(reconcile[0]));       // its Reconcile stock button opens the Reconcile screen
                reconcile[0] = new ReconcileTab(() -> tabs.getSelectionModel().select(shiftTab));
                tabs.getTabs().addAll(shiftTab, reconcile[0],
                    new OpenJerricanTab(OpenJerricanTab.Mode.OPEN), new OpenJerricanTab(OpenJerricanTab.Mode.CLOSE), new OpenJerricanTab(OpenJerricanTab.Mode.EMPTY),
                    new TransferTab(TransferTab.Mode.SEND), new TransferTab(TransferTab.Mode.RECEIVE),   // the Jerricans menu
                    new RegisterTab(), new VerifyTab(), new IncidentTab(), new InventoryTab(), new ReportsTab());
            }
            case DOSING_OPERATOR -> tabs.getTabs().addAll(new DoseTab(), new IncidentTab(), new InventoryTab());
            case DISPENSE_OPERATOR -> tabs.getTabs().addAll(new DispenseTab(), new IncidentTab());
            case WAREHOUSE_MANAGER -> tabs.getTabs().addAll(
                    new WarehouseTab(WarehouseTab.Mode.RECEIVE_JERRICAN), new WarehouseTab(WarehouseTab.Mode.REFILL),
                    new TransferTab(TransferTab.Mode.SEND), new TransferTab(TransferTab.Mode.RECEIVE),                       // the Jerricans menu
                    new WarehouseTab(WarehouseTab.Mode.RECEIVE_BULK), new StockCountTab(), new InventoryTab(), new IncidentTab(), new ReportsTab());
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            if (t instanceof RefreshableTab r) Ui.attempt(r::refresh);
        });
        setCenter(tabs);
        setLeft(sidebar(tabs));
        startIdleLock(onLogout);
        updateShiftLabel();
        if (tabs.getSelectionModel().getSelectedItem() instanceof RefreshableTab r) Ui.attempt(r::refresh);
    }

    /**
     * The screens are listed down the left, each with an icon; the tab strip itself is hidden and only holds the content.
     * Choosing an item selects its tab, and selecting a tab any other way keeps the highlighted item in step.
     */
    private VBox sidebar(TabPane tabs) {
        this.tabPane = tabs;
        VBox nav = new VBox(4);
        nav.getStyleClass().add("sidebar");
        nav.setPrefWidth(220);
        nav.setMinWidth(220);
        ToggleGroup group = new ToggleGroup();
        java.util.Map<Tab, ToggleButton> items = new java.util.HashMap<>();
        for (Tab tab : tabs.getTabs()) {
            ToggleButton item = new ToggleButton(tab.getText(), Icons.forTab(tab.getText()));
            item.getStyleClass().add("nav-item");
            item.setToggleGroup(group);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            item.setOnAction(e -> { tabs.getSelectionModel().select(tab); item.setSelected(true); });   // a screen always stays chosen
            items.put(tab, item);
            navItems.put(tab, item);
            String groupName = tab instanceof RefreshableTab r ? r.groupName() : null;
            if (groupName == null) nav.getChildren().add(item);
            else {                                                   // under a named menu: a header with the sub-items below it, indented
                item.getStyleClass().add("nav-sub");
                NavGroup g = navGroups.get(groupName);
                if (g == null) {
                    g = navGroup(groupName);
                    navGroups.put(groupName, g);
                    nav.getChildren().addAll(g.header(), g.items());
                }
                g.items().getChildren().add(item);
            }
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            ToggleButton i = items.get(t);
            if (i != null) i.setSelected(true);
            if (t instanceof RefreshableTab r && r.groupName() != null) setGroupOpen(navGroups.get(r.groupName()), true);     // the menu of the open screen is shown
        });
        ToggleButton first = items.get(tabs.getSelectionModel().getSelectedItem());
        if (first != null) first.setSelected(true);
        tabs.getStyleClass().add("no-tab-strip");
        Runnable hide = () -> { Node strip = tabs.lookup(".tab-header-area"); if (strip != null) { strip.setVisible(false); strip.setManaged(false); } };
        tabs.skinProperty().addListener((o, a, b) -> hide.run());
        javafx.application.Platform.runLater(hide);
        return nav;
    }

    /** The header of a named menu; pressing it folds the sub-items away or shows them again. */
    private NavGroup navGroup(String name) {
        Button header = new Button(name);
        header.setGraphic(Icons.forTab(name));
        header.getStyleClass().add("nav-group");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(2);
        box.getStyleClass().add("nav-sub-box");
        NavGroup g = new NavGroup(name, header, box);
        header.setOnAction(e -> setGroupOpen(g, !box.isVisible()));
        setGroupOpen(g, true);
        return g;
    }

    private void setGroupOpen(NavGroup g, boolean open) {
        if (g == null) return;
        g.items().setVisible(open);
        g.items().setManaged(open && g.header().isManaged());
        g.header().setText(g.name() + (open ? "   \u25BE" : "   \u25B8"));
    }

    /**
     * Terminals are shared between operators: after a period with no input the session is closed and the sign-in screen
     * returns. Open dialogs (e.g. a signature pad) are closed too, so nothing stays usable under the previous user.
     */
    private void startIdleLock(Runnable onLogout) {
        int seconds = org.jai.fuelmarking.config.YamlConfigurationLoader.UI.getIdleLockSeconds();
        if (seconds <= 0) return;
        javafx.animation.PauseTransition idle = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));
        idle.setOnFinished(e -> {
            System.err.println("Signed out: no input for " + seconds + " s (idle lock)");
            if (getScene() != null) {
                javafx.stage.Window main = getScene().getWindow();
                java.util.List.copyOf(javafx.stage.Window.getWindows()).stream().filter(w -> w != main).forEach(javafx.stage.Window::hide);
            }
            onLogout.run();
        });
        addEventFilter(javafx.scene.input.InputEvent.ANY, e -> idle.playFromStart());
        idle.play();
        sceneProperty().addListener((o, a, b) -> { if (b == null) idle.stop(); }); // no timer left behind after sign-out
    }

    /**
     * Until the supervisor has started a shift, every screen except Shift and Reports is hidden and locked (the services refuse those
     * operations too). Starting the shift opens the rest; closing it locks them again.
     */
    private void applyShiftGate() {
        if (tabPane == null) return;
        boolean gated = Session.get().user() != null && Session.get().user().role() == Role.SUPERVISOR && Session.get().shift() == null;
        for (var e : navItems.entrySet()) {
            boolean allowed = !gated || BEFORE_SHIFT.contains(e.getKey().getText());
            e.getValue().setVisible(allowed);
            e.getValue().setManaged(allowed);
            e.getKey().setDisable(!allowed);
        }
        for (NavGroup g : navGroups.values()) {              // a menu is shown while any of its screens is available
            boolean any = navItems.entrySet().stream().anyMatch(e -> e.getKey() instanceof RefreshableTab r && g.name().equals(r.groupName()) && e.getValue().isVisible());
            g.header().setVisible(any); g.header().setManaged(any);
            g.items().setManaged(any && g.items().isVisible());
        }
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected != null && selected.isDisable()) tabPane.getSelectionModel().select(0);       // never leave a locked screen showing
    }

    private void updateShiftLabel() {
        var shift = Session.get().shift();
        boolean usesShifts = Session.get().user() != null && Session.get().user().role() != Role.WAREHOUSE_MANAGER;   // a warehouse manager does not work in shifts
        shiftLabel.setVisible(usesShifts);
        shiftLabel.setManaged(usesShifts);
        applyShiftGate();
        shiftLabel.setText(shift == null ? "No open shift" : "Shift open since " + shift.startedAt().toLocalTime().withNano(0));
        shiftLabel.getStyleClass().remove("warn");
        if (shift == null) shiftLabel.getStyleClass().add("warn");
    }
}
