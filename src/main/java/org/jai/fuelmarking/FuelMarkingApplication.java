package org.jai.fuelmarking;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.jai.fuelmarking.config.DatabaseConfig;
import org.jai.fuelmarking.config.DevSeed;
import org.jai.fuelmarking.config.YamlConfigurationLoader.Sync;
import org.jai.fuelmarking.sync.MasterDataConsumer;
import org.jai.fuelmarking.sync.SyncAgent;
import org.jai.fuelmarking.ui.Screens;

public class FuelMarkingApplication extends Application {
    private SyncAgent syncAgent;
    private MasterDataConsumer masterData;

    @Override
    public void start(Stage stage) {
        try {
            DatabaseConfig.getDataSource(); // connects and runs Flyway migrations
            DevSeed.runIfEnabled();
        } catch (Throwable t) {
            new Alert(Alert.AlertType.ERROR, "Cannot start: database unavailable.\n" + t.getMessage()).showAndWait();
            return;
        }
        startSync();
        stage.setTitle("Fuel Marking System");
        Scene scene = new Scene(Screens.login(stage), 1200, 800);
        org.jai.fuelmarking.ui.Ui.applyTheme(scene);
        stage.setScene(scene);
        stage.show();
        showScaleSimulator();
    }

    /** With no real scale yet, the in-app simulator gets a small control window next to the main one. */
    private void showScaleSimulator() {
        if (!"simulator".equals(org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig.getScaleDriver())
                || !org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig.isSimulatorPanelEnabled()) return;
        org.jai.fuelmarking.ui.Ui.showScaleSimulatorWindow();
    }

    private void startSync() {
        if (!Sync.isEnabled()) return;
        try {
            java.util.UUID siteId = java.util.UUID.fromString(Sync.getSiteId());
            if (Sync.getApiKey().isBlank()) throw new IllegalStateException("sync.api-key is not set");
            syncAgent = new SyncAgent(Sync.getBaseUrl(), siteId, Sync.getApiKey());
            syncAgent.start(Sync.getIntervalSeconds());
            masterData = MasterDataConsumer.create(Sync.getKafkaBootstrapServers(), Sync.getMasterDataTopic(), siteId.toString(), Sync.getKafkaProperties());
            Thread t = new Thread(masterData, "master-data-consumer");
            t.setDaemon(true);
            t.start();
        } catch (RuntimeException e) {
            new Alert(Alert.AlertType.WARNING, "Synchronisation is disabled: " + e.getMessage()
                    + "\nThe app works offline; fix the sync settings and restart.").showAndWait();
        }
    }

    @Override
    public void stop() {
        if (syncAgent != null) syncAgent.close();
        if (masterData != null) masterData.close();
        DatabaseConfig.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
