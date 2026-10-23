package org.jai.fuelmarking.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.jai.fuelmarking.hardware.Hardware;
import org.jai.fuelmarking.hardware.SimulatedScale;

/**
 * Control window for the {@link SimulatedScale}: the "pan" is the number you place, the big display is what the
 * application would read. Put a container on, watch it settle, then press Read scale in the app.
 */
public final class ScaleSimulatorPanel {
    private ScaleSimulatorPanel() {}

    public static Parent create(SimulatedScale scale) {
        Label display = new Label("0.00 kg");
        display.getStyleClass().add("mono");
        display.setStyle("-fx-font-size: 44px; -fx-font-weight: bold;");
        Label state = new Label();
        VBox screen = new VBox(2, display, state);
        screen.setAlignment(Pos.CENTER);
        screen.setPadding(new Insets(12));
        screen.setStyle("-fx-background-color: #10261c; -fx-background-radius: 8;");
        display.setStyle(display.getStyle() + "-fx-text-fill: #7dffb0;");
        state.setStyle("-fx-text-fill: #7dffb0;");

        TextField weight = new TextField("16.40");
        weight.setPrefColumnCount(7);
        Button place = new Button("Place on scale");
        place.setOnAction(e -> {
            try { scale.placeOnScale(Double.parseDouble(weight.getText().trim().replace(',', '.'))); }
            catch (NumberFormatException ex) { Ui.error("Enter the weight in kilograms, e.g. 16.40"); }
            catch (IllegalArgumentException ex) { Ui.error(ex.getMessage()); }
        });
        HBox manual = new HBox(8, new Label("Weight (kg)"), weight, place);
        manual.setAlignment(Pos.CENTER_LEFT);

        Button jerrican = quick("Full jerrican 16.40", () -> scale.placeOnScale(16.40));
        Button light = quick("Light jerrican 15.90", () -> scale.placeOnScale(15.90));
        Button ibc = quick("IBC 1020", () -> scale.placeOnScale(1020));
        Button remove = quick("Remove", scale::clearPan);
        Button tare = quick("Tare", () -> { try { scale.tare(); } catch (Hardware.ScaleException ex) { Ui.error(ex.getMessage()); } });
        FlowPane quick = new FlowPane(8, 8, jerrican, light, ibc, remove, tare);

        CheckBox drifting = fault("Unsteady (never settles)", scale::setDrifting);
        CheckBox overload = fault("Overloaded", scale::setOverload);
        CheckBox unplugged = fault("Unplugged / switched off", scale::setDisconnected);
        VBox faults = new VBox(6, new Label("Faults to rehearse"), drifting, overload, unplugged);

        Label hint = new Label("Development tool. The real scale replaces this when the device is added.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #667;");

        VBox root = new VBox(14, screen, manual, quick, new Separator(), faults, hint);
        root.setPadding(new Insets(16));
        root.setPrefWidth(440);

        Timeline refresh = new Timeline(new KeyFrame(Duration.millis(200), e -> {
            try {
                Hardware.Reading r = scale.read();
                display.setText(String.format(java.util.Locale.ROOT, "%.2f kg", r.kg()));
                state.setText(r.stable() ? "STABLE" : "settling...");
            } catch (Hardware.ScaleException ex) {
                display.setText("-----");
                state.setText(scale.isDisconnected() ? "no response" : "OVERLOAD");
            }
        }));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        root.sceneProperty().addListener((o, a, b) -> { if (b == null) refresh.stop(); else refresh.play(); });
        return root;
    }

    private static Button quick(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static CheckBox fault(String text, java.util.function.Consumer<Boolean> setter) {
        CheckBox c = new CheckBox(text);
        c.selectedProperty().addListener((o, a, on) -> setter.accept(on));
        return c;
    }
}
