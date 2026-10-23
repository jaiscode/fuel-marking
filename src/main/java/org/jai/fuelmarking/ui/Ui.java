package org.jai.fuelmarking.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Function;

/** Small helpers shared by all screens. */
public final class Ui {
    private Ui() {}

    /** Runs a user action, turning business-rule violations into friendly dialogs. */
    static void attempt(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException | org.jai.fuelmarking.service.AuthService.AuthException e) {
            error(e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            error("Unexpected error: " + e.getMessage());
        }
    }

    /** Message boxes; replaceable so automated tests can answer them without a person at the screen. */
    interface Dialogs {
        void error(String msg);
        void info(String msg);
        boolean confirm(String msg);
    }

    private static final Dialogs FX_DIALOGS = new Dialogs() {
        @Override public void error(String msg) { new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait(); }
        @Override public void info(String msg) { new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait(); }
        @Override public boolean confirm(String msg) {
            return new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL)
                    .showAndWait().filter(b -> b == ButtonType.OK).isPresent();
        }
    };

    private static volatile Dialogs dialogs = FX_DIALOGS;
    private static volatile org.jai.fuelmarking.hardware.Hardware.SignaturePad signaturePad = new SignaturePadDialog();

    private static volatile org.jai.fuelmarking.hardware.Hardware.DigitalScale scale;

    /** The scale in use, chosen from configuration on first use (simulator until the real device is added). */
    static synchronized org.jai.fuelmarking.hardware.Hardware.DigitalScale scale() {
        if (scale == null) scale = org.jai.fuelmarking.hardware.ScaleFactory.create();
        return scale;
    }

    static synchronized void useScale(org.jai.fuelmarking.hardware.Hardware.DigitalScale s) { scale = s; }

    /** Opens the control window of the in-app scale simulator (no-op if another driver is configured). */
    public static void showScaleSimulatorWindow() {
        scale(); // make sure the scale (and with it the simulator) has been created
        org.jai.fuelmarking.hardware.ScaleFactory.simulator().ifPresent(sim -> {
            javafx.stage.Stage stage = new javafx.stage.Stage();
            javafx.scene.Scene scene = new javafx.scene.Scene(ScaleSimulatorPanel.create(sim));
            applyTheme(scene);
            stage.setTitle("Scale simulator");
            stage.setScene(scene);
            stage.setX(40);
            stage.setY(40);
            stage.show();
        });
    }

    static void useDialogs(Dialogs d) { dialogs = d == null ? FX_DIALOGS : d; }

    /** The signature device in use: the on-screen pad unless a hardware pad (or a test double) is installed. */
    static org.jai.fuelmarking.hardware.Hardware.SignaturePad signaturePad() { return signaturePad; }

    static void useSignaturePad(org.jai.fuelmarking.hardware.Hardware.SignaturePad pad) { signaturePad = pad == null ? new SignaturePadDialog() : pad; }

    static void error(String msg) { dialogs.error(msg); }

    static void info(String msg) { dialogs.info(msg); }

    static boolean confirm(String msg) { return dialogs.confirm(msg); }

    static <T> TableColumn<T, String> col(String title, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new SimpleStringProperty(value.apply(cd.getValue())));
        c.setPrefWidth(140);
        return c;
    }

    static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(16); g.setVgap(12); g.setPadding(new Insets(20, 24, 20, 24));
        g.getStyleClass().add("form-card");
        g.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);   // a card hugging its fields, not stretched across the screen
        return g;
    }

    static void row(GridPane g, int r, String label, javafx.scene.Node field) {
        g.add(new Label(label), 0, r);
        g.add(field, 1, r);
    }

    static VBox page(javafx.scene.Node... nodes) {
        VBox v = new VBox(14, nodes);
        v.setPadding(new Insets(20));
        return v;
    }

    /** Page heading: a title with a one-line explanation underneath. */
    static VBox header(String title, String subtitle) {
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        VBox v = new VBox(2, t);
        if (subtitle != null && !subtitle.isBlank()) {
            Label s = new Label(subtitle);
            s.getStyleClass().add("hint");
            s.setWrapText(true);
            v.getChildren().add(s);
        }
        return v;
    }

    /** A small heading above a table or group. */
    static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    /** A small upper-case caption, e.g. above a figure. */
    static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("kpi-label");
        return l;
    }

    /** An input with a small caption above it. */
    static VBox field(String caption, javafx.scene.Node input) {
        Label l = new Label(caption.toUpperCase());
        l.getStyleClass().add("kpi-label");
        return new VBox(4, l, input);
    }

    /** A white rounded panel around the given content. */
    static VBox card(javafx.scene.Node... nodes) {
        VBox v = new VBox(12, nodes);
        v.getStyleClass().add("card");
        return v;
    }

    /** A figure card: small caption, big value, one-line note. */
    static VBox kpi(String name, Label value, String note) {
        Label n = new Label(name);
        n.getStyleClass().add("kpi-label");
        value.getStyleClass().add("big-number");
        Label h = new Label(note);
        h.getStyleClass().add("hint");
        h.setWrapText(true);                       // a long caption wraps instead of being cut off
        VBox box = new VBox(2, n, value, h);
        box.getStyleClass().addAll("card", "kpi");
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Shows a status as a coloured pill (SEALED, OPENED, EMPTY, IN_TRANSIT, ...). */
    static <T> void pillColumn(TableColumn<T, String> col) {
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null || s.isBlank()) { setGraphic(null); setText(null); return; }
                Label pill = new Label(s.replace('_', ' '));
                pill.getStyleClass().addAll("pill", "pill-" + s.toLowerCase().replace('_', '-'));
                setGraphic(pill);
                setText(null);
            }
        });
    }

    /** Larger touch-friendly controls; call for every Scene. */
    public static void applyTheme(javafx.scene.Scene scene) {
        var css = Ui.class.getResource("/css/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
    }

    static <B extends Button> B primary(B b) {
        b.getStyleClass().add("primary");
        return b;
    }

    static String fmt(double amount) { return String.format("%,.1f", amount); }

    /** Container quantities are stored in grams but handled by people in kilograms (a jerrican holds 18-22 kg, an IBC 900-1000 kg). */
    static String kg(double grams) {
        String s = String.format(java.util.Locale.ROOT, "%,.3f", grams / 1000.0);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** Whether rum amounts in the shift reconciliation are shown in kilograms (the choice is kept while the app runs); grams otherwise. */
    static volatile boolean rumInKg = false;

    /** A rum amount in the unit the user chose: "900.0 g" or "0.9 kg" (kilograms with up to four decimals, so 0.1 g is not lost). */
    static String rumAmount(double grams) {
        if (!rumInKg) return fmt(grams) + " g";
        String s = String.format(java.util.Locale.ROOT, "%,.4f", grams / 1000.0);
        return (s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s) + " kg";
    }

    /** Kilograms typed by a person, as grams to one decimal. */
    static double gramsFromKg(String text, String what) {
        double kg;
        try { kg = Double.parseDouble(text.trim().replace(",", "")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be a number of kg"); }
        return Math.round(kg * 10_000.0) / 10.0;
    }
}
