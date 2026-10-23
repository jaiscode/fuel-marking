package org.jai.fuelmarking.hardware;

import org.jai.fuelmarking.ui.ScaleSimulatorApp;

/**
 * Starts the standalone scale simulator (a window plus a TCP server) from the packaged jar:
 * {@code java -cp fuel-marking-desktop.jar org.jai.fuelmarking.hardware.ScaleSimulatorLauncher [port]}.
 * Then run the main app with {@code hardware.scale.driver=tcp} to use it exactly like a real networked scale.
 */
public final class ScaleSimulatorLauncher {
    private ScaleSimulatorLauncher() {}

    public static void main(String[] args) {
        ScaleSimulatorApp.main(args);
    }
}
