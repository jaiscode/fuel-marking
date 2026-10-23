package org.jai.fuelmarking.hardware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedScaleTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    SimulatedScale scale;

    @BeforeEach
    void setUp() { scale = new SimulatedScale(now::get, new Random(42), 800, 1500); }

    @Test
    void anEmptyPanReadsZeroAndIsSteadyFromTheStart() {
        assertEquals(new Hardware.Reading(0.0, true), scale.read());
    }

    @Test
    void aNewWeightJittersThenSettlesToTheExactValue() {
        scale.placeOnScale(16.40);
        Hardware.Reading first = scale.read();
        assertFalse(first.stable());
        assertEquals(16.40, first.kg(), 0.33);

        now.addAndGet(400);                                   // half way: still moving, but closer
        Hardware.Reading mid = scale.read();
        assertFalse(mid.stable());
        assertEquals(16.40, mid.kg(), 0.17);

        now.addAndGet(400);                                   // settled
        assertEquals(new Hardware.Reading(16.40, true), scale.read());
        assertEquals(new Hardware.Reading(16.40, true), scale.read(), "and it stays put");
    }

    @Test
    void readingsAreRoundedToTheDisplayResolutionOfOneTenthOfAGram() {
        scale.placeOnScale(16.40494);
        now.addAndGet(5_000);
        assertEquals(16.4049, scale.read().kg());
        assertEquals(16404.9, scale.read().grams(), 1e-6);
    }

    @Test
    void aSmallDoseHasOnlyAFewGramsOfJitterWhileSettling() {
        scale.placeOnScale(0.100);                            // 100 g of rum in a container already tared
        Hardware.Reading r = scale.read();
        assertFalse(r.stable());
        assertEquals(100.0, r.grams(), 3.0);
        now.addAndGet(1_000);
        assertEquals(100.0, scale.read().grams(), 1e-9);
    }

    @Test
    void tareZeroesTheDisplayAndLaterWeightIsNet() {
        scale.placeOnScale(2.0);                               // an empty pallet
        now.addAndGet(1_000);
        scale.tare();
        now.addAndGet(1_000);
        assertEquals(new Hardware.Reading(0.0, true), scale.read());
        scale.placeOnScale(18.40);                             // pallet + jerrican
        now.addAndGet(1_000);
        assertEquals(16.40, scale.read().kg());
    }

    @Test
    void anUnsteadySimulatedScaleNeverSettles() {
        scale.placeOnScale(16.40);
        scale.setDrifting(true);
        for (int i = 0; i < 20; i++) {
            now.addAndGet(5_000);
            assertFalse(scale.read().stable());
        }
        scale.setDrifting(false);
        assertTrue(scale.read().stable());
    }

    @Test
    void overloadAndUnpluggedAreReportedAsFaults() {
        scale.placeOnScale(1_600);                             // above the 1500 kg capacity
        assertTrue(assertThrows(Hardware.ScaleException.class, scale::read).getMessage().contains("overloaded"));
        scale.placeOnScale(10);
        now.addAndGet(1_000);
        assertEquals(10.0, scale.read().kg());
        scale.setOverload(true);
        assertTrue(assertThrows(Hardware.ScaleException.class, scale::read).getMessage().contains("overloaded"));
        scale.setOverload(false);

        scale.setDisconnected(true);
        assertTrue(assertThrows(Hardware.ScaleException.class, scale::read).getMessage().contains("not responding"));
        assertThrows(Hardware.ScaleException.class, scale::tare);
        scale.setDisconnected(false);
        assertEquals(10.0, scale.read().kg());
    }

    @Test
    void nonsenseWeightsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> scale.placeOnScale(-1));
        assertThrows(IllegalArgumentException.class, () -> scale.placeOnScale(Double.NaN));
    }
}
