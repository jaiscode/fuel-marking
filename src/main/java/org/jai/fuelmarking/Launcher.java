package org.jai.fuelmarking;

/**
 * Entry point for the packaged jar. A JavaFX Application subclass cannot be a jar's main class when JavaFX is on the
 * classpath (the launcher then looks for the JavaFX modules), so the jar starts here and delegates.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--selfcheck")) {
            System.exit(SelfCheck.run(System.out));   // diagnose this installation without starting the application
        }
        FuelMarkingApplication.main(args);
    }
}
