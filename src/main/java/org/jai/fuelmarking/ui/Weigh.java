package org.jai.fuelmarking.ui;

import javafx.concurrent.Task;
import javafx.scene.control.Button;
import org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig;
import org.jai.fuelmarking.hardware.Hardware;

import java.util.Locale;
import java.util.function.Consumer;

/** "Read scale" behaviour shared by the screens: wait for a steady reading off the UI thread, then hand over the kilograms. */
final class Weigh {
    private Weigh() {}

    static String formatKg(double kg) { return String.format(Locale.ROOT, "%.2f", kg); }

    /** Grams to one decimal (the scale resolves 0.1 g), as shown to the operator and stored. */
    static double roundGrams(double kg) { return Math.round(kg * 10_000.0) / 10.0; }

    static String formatGrams(double grams) { return String.format(Locale.ROOT, "%.1f", grams); }

    /** Zeroes the scale (tare) off the UI thread; the live display shows the result. */
    static void tare(Button trigger) {
        Hardware.DigitalScale scale = Ui.scale();
        String label = trigger.getText();
        trigger.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() { scale.tare(); return null; }
        };
        task.setOnSucceeded(e -> { trigger.setText(label); trigger.setDisable(false); });
        task.setOnFailed(e -> {
            trigger.setText(label);
            trigger.setDisable(false);
            Throwable t = task.getException();
            Ui.error(t instanceof Hardware.ScaleException ? t.getMessage() : "Could not tare the scale: " + t.getMessage());
        });
        Thread thread = new Thread(task, "tare");
        thread.setDaemon(true);
        thread.start();
    }

    static void readStable(Button trigger, Consumer<Double> onKg) {
        Hardware.DigitalScale scale = Ui.scale();
        String label = trigger.getText();
        trigger.setDisable(true);
        trigger.setText("Weighing...");
        long waitMillis = HardwareConfig.getStableWaitSeconds() * 1000L;

        Task<Double> task = new Task<>() {
            @Override
            protected Double call() throws Exception {
                long end = System.currentTimeMillis() + waitMillis;
                while (true) {
                    Hardware.Reading r = scale.read();
                    if (r.stable()) {
                        if (r.kg() < 0)
                            throw new Hardware.ScaleException("The scale reads below zero (" + formatKg(r.kg()) + " kg). Put the container back on the scale.");
                        if (r.kg() == 0)
                            throw new Hardware.ScaleException("Nothing to weigh: the scale reads zero. Put the container on the scale (after taring an empty container, add the rum first).");
                        return r.kg();
                    }
                    if (System.currentTimeMillis() >= end)
                        throw new Hardware.ScaleException("The reading is not steady. Keep the container still and fully on the scale, then try again.");
                    Thread.sleep(100);
                }
            }
        };
        task.setOnSucceeded(e -> {
            trigger.setText(label);
            trigger.setDisable(false);
            onKg.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            trigger.setText(label);
            trigger.setDisable(false);
            Throwable t = task.getException();
            Ui.error(t instanceof Hardware.ScaleException ? t.getMessage() : "Could not read the scale: " + t.getMessage());
        });
        Thread thread = new Thread(task, "weigh");
        thread.setDaemon(true);
        thread.start();
    }
}
