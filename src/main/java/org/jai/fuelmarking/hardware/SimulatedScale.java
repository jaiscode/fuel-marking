package org.jai.fuelmarking.hardware;

import java.util.Random;
import java.util.function.LongSupplier;

/**
 * An in-memory scale that behaves like the real thing: after something is put on the pan the display jitters and
 * only becomes stable after a settling time; it can be zeroed (tared), overloaded, drift forever, or "unplugged".
 * Time and noise are injectable so tests are deterministic.
 */
public class SimulatedScale implements Hardware.DigitalScale {
    public static final double DEFAULT_MAX_KG = 1500;

    private final LongSupplier clock;
    private final Random random;
    private final long settleMillis;
    private final double maxKg;

    private volatile double gross;
    private volatile double tare;
    private volatile long changedAt;
    private volatile boolean drifting;
    private volatile boolean overload;
    private volatile boolean disconnected;

    public SimulatedScale() { this(System::currentTimeMillis, new Random(), 800, DEFAULT_MAX_KG); }

    public SimulatedScale(LongSupplier clock, Random random, long settleMillis, double maxKg) {
        this.clock = clock;
        this.random = random;
        this.settleMillis = settleMillis;
        this.maxKg = maxKg;
        this.changedAt = clock.getAsLong() - settleMillis; // an empty pan at start-up is already settled
    }

    // ---- what the person at the scale does
    public void placeOnScale(double kg) {
        if (Double.isNaN(kg) || kg < 0) throw new IllegalArgumentException("Weight must be zero or more");
        gross = kg;
        changedAt = clock.getAsLong();
    }

    public void clearPan() { placeOnScale(0); }

    /** Faults to rehearse. */
    public void setDrifting(boolean on) { drifting = on; }
    public void setOverload(boolean on) { overload = on; }
    public void setDisconnected(boolean on) { disconnected = on; }

    public boolean isDrifting() { return drifting; }
    public boolean isOverload() { return overload; }
    public boolean isDisconnected() { return disconnected; }
    public double grossKg() { return gross; }
    public double tareKg() { return tare; }

    // ---- what the application does
    @Override
    public Hardware.Reading read() {
        if (disconnected) throw new Hardware.ScaleException("The scale is not responding - check that it is switched on and connected.");
        if (overload || gross > maxKg) throw new Hardware.ScaleException("The scale is overloaded - remove the weight.");
        double net = gross - tare;
        long elapsed = Math.max(0, clock.getAsLong() - changedAt);
        boolean settled = !drifting && elapsed >= settleMillis;
        // jitter is proportional to the load (a 100 g dose wobbles by a few grams, a 16 kg jerrican by a few hundred)
        double size = Math.abs(net);
        double noise = 0;
        if (drifting) noise = (random.nextDouble() * 2 - 1) * Math.max(0.001, 0.005 * size);
        else if (!settled) noise = (random.nextDouble() * 2 - 1) * Math.max(0.002, 0.02 * size) * (1 - elapsed / (double) settleMillis); // fades as it settles
        return new Hardware.Reading(roundToDisplay(net + noise), settled);
    }

    @Override
    public void tare() {
        if (disconnected) throw new Hardware.ScaleException("The scale is not responding - check that it is switched on and connected.");
        tare = gross;
        changedAt = clock.getAsLong();
    }

    @Override
    public String name() { return "simulator"; }

    /** The display resolves 0.1 g (0.0001 kg), as the precision scales used for dosing do. */
    private static double roundToDisplay(double kg) { return Math.round(kg * 10_000.0) / 10_000.0; }
}
