package org.jai.fuelmarking.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig;
import org.jai.fuelmarking.hardware.ScaleSimulatorServer;
import org.jai.fuelmarking.hardware.SimulatedScale;

/** Standalone scale simulator: a control window plus a TCP server the main app connects to with driver=tcp. */
public class ScaleSimulatorApp extends Application {
    private ScaleSimulatorServer server;

    @Override
    public void start(Stage stage) throws Exception {
        int port = getParameters().getRaw().isEmpty() ? HardwareConfig.getScaleTcpPort() : Integer.parseInt(getParameters().getRaw().get(0));
        SimulatedScale scale = new SimulatedScale();
        server = new ScaleSimulatorServer(scale, port, 200);
        Scene scene = new Scene(ScaleSimulatorPanel.create(scale));
        Ui.applyTheme(scene);
        stage.setTitle("Scale simulator - 127.0.0.1:" + server.port());
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (server != null) server.close();
    }

    public static void main(String[] args) { launch(args); }
}
