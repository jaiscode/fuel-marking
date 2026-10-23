package org.jai.fuelmarking.hardware;

import org.jai.fuelmarking.config.YamlConfigurationLoader.HardwareConfig;

import java.util.Optional;

/**
 * Chooses the scale from configuration ({@code hardware.scale.driver}):
 * <ul>
 *   <li>{@code simulator} - in-process {@link SimulatedScale}, controlled from the simulator window (default);</li>
 *   <li>{@code tcp} - a device or the standalone simulator at {@code hardware.scale.tcp.host/port};</li>
 *   <li>{@code serial} - reserved for the real device: add it here (see docs/hardware.md).</li>
 * </ul>
 */
public final class ScaleFactory {
    private static SimulatedScale simulator;

    private ScaleFactory() {}

    public static synchronized Hardware.DigitalScale create() {
        String driver = HardwareConfig.getScaleDriver();
        switch (driver) {
            case "simulator" -> {
                simulator = new SimulatedScale();
                return simulator;
            }
            case "tcp" -> {
                return new TcpScale(HardwareConfig.getScaleTcpHost(), HardwareConfig.getScaleTcpPort());
            }
            default -> {
                return new Unavailable(driver);
            }
        }
    }

    /** The in-process simulator, if that driver is in use (so its control window can drive it). */
    public static synchronized Optional<SimulatedScale> simulator() { return Optional.ofNullable(simulator); }

    /** Stands in for a driver that is not available, so the app starts and the operator gets a clear message. */
    private record Unavailable(String driver) implements Hardware.DigitalScale {
        private Hardware.ScaleException problem() {
            return new Hardware.ScaleException("The scale driver '" + driver + "' is not available yet. "
                    + "Set hardware.scale.driver to 'simulator' or 'tcp' (see docs/hardware.md).");
        }

        @Override public Hardware.Reading read() { throw problem(); }
        @Override public void tare() { throw problem(); }
        @Override public String name() { return driver + " (unavailable)"; }
    }
}
