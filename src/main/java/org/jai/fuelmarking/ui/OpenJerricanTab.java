package org.jai.fuelmarking.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jai.fuelmarking.model.Records.Container;
import org.jai.fuelmarking.service.ContainerService;
import org.jai.fuelmarking.service.IncidentService;
import org.jai.fuelmarking.service.ReconcileService;
import org.jai.fuelmarking.session.Session;

import java.util.List;
import java.util.Map;

/**
 * A jerrican at the marking site, in three screens under the Jerricans menu. "Open jerrican": the sealed jerricans waiting to be opened are cards in a
 * list on the left (scan a barcode to jump to one); on the right, check the seals and open it. "Close jerrican": the opened jerricans in use are the
 * cards, and on the right the chosen one is closed back with a new seal (it still holds rum). "Declare empty": the same opened jerricans, and the chosen
 * one is declared empty (used up).
 */
class OpenJerricanTab extends RefreshableTab {
    /** Which of the screens this is. */
    enum Mode { OPEN, CLOSE, EMPTY }
    private final boolean closing;                                    // false: the Open jerrican screen; true: one of the two that work on opened jerricans
    private final boolean emptying;                                   // the Declare empty screen (as opposed to Close jerrican)
    private final ContainerService service = new ContainerService();
    private final TextField barcode = new TextField();                // scan (or type) a barcode to find a jerrican
    private final ToggleButton sealedTab = new ToggleButton(), openedTab = new ToggleButton();
    private final ListView<Container> jerricans = new ListView<>();
    private final Label sealedCount = new Label(), openedCount = new Label(), openedKg = new Label();

    // action: open a sealed jerrican
    private final Label openTitle = new Label(), openInfo = new Label();
    private final TextField topSeal = new TextField();
    private final TextField bottomSeal = new TextField();
    // action: an opened jerrican - close it back, or declare it empty
    private final Label useTitle = new Label(), useInfo = new Label();
    private final TextField closeTop = new TextField();
    private final TextField closeBottom = new TextField();
    private final ComboBox<String> leftCause = new ComboBox<>();
    private final TextField leftNote = new TextField();

    private final VBox nothingChosen = new VBox(), openPane = new VBox(14), usePane = new VBox(14);
    private List<Container> sealedItems = List.of(), openedItems = List.of();
    private Map<String, String> colors = Map.of();

    OpenJerricanTab(Mode mode) {
        super(mode == Mode.OPEN ? "Open jerrican" : mode == Mode.CLOSE ? "Close jerrican" : "Declare empty");
        this.closing = mode != Mode.OPEN;
        this.emptying = mode == Mode.EMPTY;
        inGroup("Jerricans");

        // ---- left: find a jerrican, choose from the cards
        barcode.setPromptText("Scan or type a jerrican barcode");
        barcode.setOnAction(e -> Ui.attempt(this::findScanned));
        sealedTab.setToggleGroup(new ToggleGroup());
        openedTab.setToggleGroup(sealedTab.getToggleGroup());
        for (ToggleButton t : List.of(sealedTab, openedTab)) { t.getStyleClass().add("segment"); t.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(t, Priority.ALWAYS); }
        (closing ? openedTab : sealedTab).setSelected(true);
        sealedTab.getToggleGroup().selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { was.setSelected(true); return; }                 // one list is always showing
            showList();
        });
        HBox segments = new HBox(0, sealedTab, openedTab);
        segments.getStyleClass().add("segments");
        segments.setVisible(false);                                    // each screen has just one list; the switch is only kept so the code stays in one place
        segments.setManaged(false);
        jerricans.getStyleClass().add("jar-list");
        jerricans.setCellFactory(lv -> new JarCell());
        jerricans.setPlaceholder(new Label("Nothing here"));
        jerricans.setPrefHeight(420);
        VBox.setVgrow(jerricans, Priority.ALWAYS);
        jerricans.getSelectionModel().selectedItemProperty().addListener((o, a, k) -> chosen(k));
        VBox left = Ui.card(barcode, segments, jerricans);
        left.setPrefWidth(430);
        left.setMinWidth(380);

        // ---- right: the action for the chosen jerrican
        Label empty = new Label("Choose a jerrican from the list, or scan its barcode.");
        empty.getStyleClass().add("hint");
        empty.setWrapText(true);
        nothingChosen.getChildren().add(empty);
        nothingChosen.setAlignment(Pos.CENTER);
        nothingChosen.setMinHeight(200);

        openTitle.getStyleClass().add("section-title");
        openInfo.getStyleClass().add("hint");
        openInfo.setWrapText(true);
        topSeal.setPromptText("Seal number on the jerrican");
        bottomSeal.setPromptText("Second seal (jerricans with two seals)");
        Button open = Ui.primary(new Button("Seals checked - open jerrican"));
        open.setOnAction(e -> Ui.attempt(this::open));
        open.setMaxWidth(Double.MAX_VALUE);
        openPane.getChildren().addAll(openTitle, openInfo, Ui.field("Seal", topSeal), Ui.field("Second seal", bottomSeal), open);

        useTitle.getStyleClass().add("section-title");
        useInfo.getStyleClass().add("hint");
        useInfo.setWrapText(true);
        closeTop.setPromptText("New seal number");
        closeBottom.setPromptText("Second new seal (two-seal jerricans)");
        Button closeBack = Ui.primary(new Button("Close jerrican back & sign"));
        closeBack.setOnAction(e -> Ui.attempt(this::closeBack));
        closeBack.setMaxWidth(Double.MAX_VALUE);
        Label notFinished = Ui.section("Not finished - it still holds rum");
        Label closeHint = hint("Put the cap back and fit a NEW seal (the one it was opened with is broken). It returns to sealed stock for the next shift.");
        leftCause.getItems().setAll(IncidentService.TYPES);
        leftCause.setPromptText("What happened to the rum left?");
        leftCause.setMaxWidth(Double.MAX_VALUE);
        leftNote.setPromptText("Note (needed for other)");
        Button declare = Ui.primary(new Button("Declare empty & sign"));
        declare.setOnAction(e -> Ui.attempt(this::declareEmpty));
        declare.setMaxWidth(Double.MAX_VALUE);
        Label finished = Ui.section("Finished - it is used up");
        Label emptyHint = hint("Declare it empty: its seals are discarded, and it goes back to the warehouse without them (from the Transfer jerrican screen) to be sealed again when it is refilled.");
        if (emptying)       // Declare empty: only what is needed to declare the chosen jerrican empty
            usePane.getChildren().addAll(useTitle, useInfo, new Separator(), finished, emptyHint, Ui.field("If rum is left", leftCause), Ui.field("Note", leftNote), declare);
        else                // Close jerrican: only what is needed to close the chosen jerrican back with a new seal
            usePane.getChildren().addAll(useTitle, useInfo, new Separator(), notFinished, closeHint, Ui.field("New seal", closeTop), Ui.field("Second new seal", closeBottom), closeBack);

        StackPane actions = new StackPane(nothingChosen, openPane, usePane);
        StackPane.setAlignment(nothingChosen, Pos.TOP_CENTER);
        for (Region r : List.of(openPane, usePane)) StackPane.setAlignment(r, Pos.TOP_LEFT);
        VBox right = Ui.card(actions);
        HBox.setHgrow(right, Priority.ALWAYS);
        showPane(null);

        HBox figures = closing
                ? new HBox(14, Ui.kpi("OPENED NOW", openedCount, "Close each away before ending the shift"), Ui.kpi("RUM IN OPENED JERRICANS", openedKg, "What they still hold"))
                : new HBox(14, Ui.kpi("SEALED IN STOCK", sealedCount, "Ready to be opened"), Ui.kpi("OPENED NOW", openedCount, "Already open: close them, or declare them empty"));
        HBox body = new HBox(14, left, right);
        setContent(new ScrollPane(Ui.page(emptying
                ? Ui.header("Declare empty", "When an opened jerrican is used up, declare it empty: its seals are discarded and it can go back to the warehouse to be refilled.")
                : closing
                ? Ui.header("Close jerrican", "At the end of the shift close each opened jerrican that still holds rum back with a new seal. A jerrican that is used up is declared empty instead.")
                : Ui.header("Open jerrican", "Check the seals, then open a sealed jerrican so it can be used for dosing."),
                figures, body)) {{ setFitToWidth(true); }});
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    /** One jerrican as a card: rum colour, barcode, type and rum type, and the rum it holds. */
    private final class JarCell extends ListCell<Container> {
        @Override protected void updateItem(Container k, boolean empty) {
            super.updateItem(k, empty);
            if (empty || k == null) { setGraphic(null); setText(null); return; }
            Label title = new Label(k.barcode());
            title.getStyleClass().addAll("jar-title", "mono");
            Label sub = new Label(k.type().name() + (k.rumTypeCode() == null ? "" : "  -  " + k.rumTypeCode()));
            sub.getStyleClass().add("hint");
            Label kg = new Label(Ui.kg(k.currentVolumeMl()) + " kg");
            kg.getStyleClass().add("jar-title");
            Label what = new Label("SEALED".equals(k.status()) ? k.type().seals() + (k.type().seals() == 1 ? " seal" : " seals") : "in it");
            what.getStyleClass().add("hint");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            VBox amount = new VBox(1, kg, what);
            amount.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, RumSwatch.square(colors.get(k.rumTypeCode())), new VBox(1, title, sub), grow, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 10, 8, 10));
            row.getStyleClass().add("jar-card");
            setGraphic(row);
            setText(null);
        }
    }

    private void showList() {
        Container keep = jerricans.getSelectionModel().getSelectedItem();
        jerricans.getItems().setAll(sealedTab.isSelected() ? sealedItems : openedItems);
        jerricans.setPlaceholder(new Label(sealedTab.isSelected() ? "No sealed jerricans in stock" : "No opened jerricans"));
        if (keep != null) jerricans.getItems().stream().filter(x -> x.barcode().equals(keep.barcode())).findFirst().ifPresent(jerricans.getSelectionModel()::select);
        chosen(jerricans.getSelectionModel().getSelectedItem());
    }

    /** The barcode box: jump to that jerrican, in whichever list it is. */
    private void findScanned() {
        String code = barcode.getText() == null ? "" : barcode.getText().trim();
        if (code.isEmpty()) return;
        List<Container> here = closing ? openedItems : sealedItems, other = closing ? sealedItems : openedItems;
        Container k = here.stream().filter(x -> x.barcode().equalsIgnoreCase(code)).findFirst().orElse(null);
        if (k == null) {
            if (other.stream().anyMatch(x -> x.barcode().equalsIgnoreCase(code)))
                throw new IllegalArgumentException(code + (closing ? " is sealed - open it on the Open jerrican screen first"
                        : " is already open - close it on the Close jerrican screen, or declare it empty on the Declare empty screen"));
            throw new IllegalArgumentException("No " + (closing ? "opened" : "sealed") + " jerrican " + code + " at this site");
        }
        select(k);
    }

    private void select(Container k) {
        jerricans.getItems().stream().filter(x -> x.barcode().equals(k.barcode())).findFirst().ifPresent(x -> {
            jerricans.getSelectionModel().select(x);
            jerricans.scrollTo(x);
        });
    }

    private void chosen(Container k) {
        if (k != null && !barcode.getText().equalsIgnoreCase(k.barcode())) barcode.setText(k.barcode());
        showPane(k);
    }

    private void showPane(Container k) {
        boolean sealed = k != null && "SEALED".equals(k.status()), used = k != null && "OPENED".equals(k.status());
        nothingChosen.setVisible(k == null); nothingChosen.setManaged(k == null);
        openPane.setVisible(sealed); openPane.setManaged(sealed);
        usePane.setVisible(used); usePane.setManaged(used);
        if (sealed) {
            openTitle.setText("Open " + k.barcode());
            openInfo.setText(k.type().name() + ", " + Ui.kg(k.currentVolumeMl()) + " kg, " + k.type().seals() + (k.type().seals() == 1 ? " seal" : " seals")
                    + ". Check the seal number" + (k.type().seals() == 1 ? "" : "s") + " on the jerrican against what you type, then open it.");
            topSeal.clear(); bottomSeal.clear();
            bottomSeal.setDisable(k.type().seals() < 2);
        } else if (used) {
            closeBottom.setDisable(k.type().seals() < 2);
            if (closeBottom.isDisabled()) closeBottom.clear();
            boolean rumLeft = k.currentVolumeMl() >= ReconcileService.TOLERANCE_G;
            leftCause.setDisable(!rumLeft);
            leftNote.setDisable(!rumLeft);
            if (!rumLeft) { leftCause.setValue(null); leftNote.clear(); }
            useTitle.setText(k.barcode() + " is open");
            useInfo.setText(emptying
                    ? (rumLeft ? "It still holds " + Ui.kg(k.currentVolumeMl()) + " kg of rum. Declare it empty only if it is really finished, and say what happened to the rum (recorded as an incident). "
                            + "If it is not finished, close it back with a new seal on the Close jerrican screen."
                            : "It is used up. Declare it empty (its seals are discarded), then send it back to the warehouse from the Transfer jerrican screen.")
                    : (rumLeft ? "It still holds " + Ui.kg(k.currentVolumeMl()) + " kg of rum. Put the cap back and close it back with a new seal."
                            : "It is used up, so there is nothing to close back: declare it empty on the Declare empty screen."));
        }
    }

    private void open() {
        Session s = Session.get();
        String code = barcode.getText().trim();
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign to confirm seals opened for " + code);
        if (sig == null) return;
        service.openJerrican(s.user(), s.site(), s.shift(), code, topSeal.getText().trim(), bottomSeal.getText().trim(), sig);
        topSeal.clear(); bottomSeal.clear(); barcode.clear();
        refresh();                                                        // it is in use now: it moves to the Close jerrican screen
        Ui.info("Jerrican opened.");
    }

    private Container chosenOpened() {
        Container k = jerricans.getSelectionModel().getSelectedItem();
        if (k == null || !"OPENED".equals(k.status())) throw new IllegalArgumentException("Choose an opened jerrican first");
        return k;
    }

    private void closeBack() {
        Session s = Session.get();
        Container k = chosenOpened();
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign to close " + k.barcode() + " back with a new seal");
        if (sig == null) return;
        service.closeBack(s.user(), s.site(), s.shift(), k.barcode(), closeTop.getText().trim(), closeBottom.getText().trim(), sig);
        closeTop.clear(); closeBottom.clear();
        refresh();
        Ui.info(k.barcode() + " is closed back with its new seal and is in sealed stock again.");
    }

    private void declareEmpty() {
        Session s = Session.get();
        Container k = chosenOpened();
        boolean rumLeft = k.currentVolumeMl() >= ReconcileService.TOLERANCE_G;
        if (rumLeft && leftCause.getValue() == null)
            throw new IllegalArgumentException(Ui.kg(k.currentVolumeMl()) + " kg of rum is still shown in " + k.barcode() + " - say what happened to it: spillage, leakage or other");
        if (!Ui.confirm("Declare " + k.barcode() + " empty?" + (rumLeft ? "\n\n" + Ui.kg(k.currentVolumeMl()) + " kg of rum will be recorded as an incident ("
                + leftCause.getValue().toLowerCase() + ")." : "") + "\n\nIts seal(s) will be discarded. It can then be sent back to the warehouse, without seals, to be refilled and sealed again.")) return;
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign to declare " + k.barcode() + " empty");
        if (sig == null) return;
        service.declareEmpty(s.user(), s.site(), s.shift(), k.barcode(), leftCause.getValue(), leftNote.getText(), sig);
        leftCause.setValue(null); leftNote.clear();
        refresh();
        Ui.info(k.barcode() + " is empty. Send it back to the warehouse from the Transfer jerrican screen.");
    }

    @Override
    void refresh() {
        colors = org.jai.fuelmarking.config.Tx.call(c -> new org.jai.fuelmarking.dao.MasterDataDao().rumColors(c));
        var all = service.inventory(Session.get().site().id()).stream().filter(k -> !k.type().isBulk()).toList();
        sealedItems = all.stream().filter(k -> "SEALED".equals(k.status())).toList();
        openedItems = all.stream().filter(k -> "OPENED".equals(k.status())).toList();
        sealedTab.setText("Sealed (" + sealedItems.size() + ")");
        openedTab.setText("Opened (" + openedItems.size() + ")");
        sealedCount.setText(String.valueOf(sealedItems.size()));
        openedCount.setText(String.valueOf(openedItems.size()));
        openedKg.setText(Ui.kg(openedItems.stream().mapToDouble(Container::currentVolumeMl).sum()) + " kg");
        showList();
    }
}
