package org.jai.fuelmarking.hardware;

import java.util.function.Consumer;

/** Device abstractions so the app runs without the physical hardware. */
public final class Hardware {
    private Hardware() {}

    public interface BarcodeScanner {
        /** Registers a listener invoked with every scanned code. */
        void onScan(Consumer<String> listener);
    }

    /** One weighing result from the scale. {@code stable} is true once the display has settled (a real scale's "ST" flag). */
    public record Reading(double kg, boolean stable) {
        /** Rum quantities in this system are grams; the scale's unit is kilograms. */
        public double grams() { return kg * 1000.0; }
    }

    /** The scale could not give a usable reading: not connected, silent, overloaded, closed... The message is for the operator. */
    public static class ScaleException extends RuntimeException {
        public ScaleException(String message) { super(message); }
    }

    /**
     * A digital scale. Implementations: {@link SimulatedScale} (in-process, for development and tests), {@link TcpScale}
     * (a device or simulator reachable over TCP) and, later, a serial-port scale built on {@link StreamScale}.
     */
    public interface DigitalScale {
        /** The current reading. Callers that need an exact weight must wait for {@code stable}. Throws {@link ScaleException}. */
        Reading read();

        /** Zeroes the display with whatever is on the pan (tare). Throws {@link ScaleException}. */
        void tare();

        /** Short description for messages ("simulator", "TCP localhost:5025"). */
        String name();
    }

    public interface SignaturePad {
        /** Blocks until the signer confirms; returns the signature as PNG bytes, or null if cancelled. */
        byte[] capture(String prompt);
    }
}
