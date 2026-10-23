package org.jai.fuelmarking.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.jai.fuelmarking.hardware.Hardware;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A live readout of the scale (net weight in grams) so the operator can watch the container fill while dosing or
 * refilling. Polls only while it is on screen, off the UI thread.
 */
final class ScaleDisplay extends HBox {
    private final Label value = new Label("--");
    private final Label state = new Label();
    private ScheduledExecutorService poller;

    ScaleDisplay() {
        super(14);
        setAlignment(Pos.CENTER_LEFT);
        value.getStyleClass().add("mono");
        value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #7dffb0;");
        state.setStyle("-fx-text-fill: #7dffb0;");
        setStyle("-fx-background-color: #10261c; -fx-background-radius: 8; -fx-padding: 8 16;");
        getChildren().addAll(new Label("Scale"), value, state);
        ((Label) getChildren().get(0)).setStyle("-fx-text-fill: #9bc9ad;");
        sceneProperty().addListener((o, was, now) -> { if (now != null) start(); else stop(); });
    }

    private synchronized void start() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scale-display");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 0, 300, TimeUnit.MILLISECONDS);
    }

    private synchronized void stop() {
        if (poller != null) poller.shutdownNow();
        poller = null;
    }

    private void poll() {
        try {
            Hardware.Reading r = Ui.scale().read();
            Platform.runLater(() -> {
                value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #7dffb0;");
                value.setText(String.format(Locale.ROOT, "%,.1f g", r.grams()));
                state.setText(r.stable() ? "steady" : "settling...");
            });
        } catch (RuntimeException e) {
            Platform.runLater(() -> {
                value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #ff9d8a;");
                value.setText("----");
                state.setText(e.getMessage() == null ? "scale unavailable" : e.getMessage());
            });
        }
    }
}
