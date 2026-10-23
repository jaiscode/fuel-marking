package org.jai.fuelmarking.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncJitterTest {
    @Test
    void theDelayStaysWithinTwentyPercentOfTheIntervalAndVaries() {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            long d = SyncAgent.jittered(100);
            assertTrue(d >= 80 && d <= 120, "was " + d);
            seen.add(d);
        }
        assertTrue(seen.size() > 10, "it must actually vary");
        assertEquals(1, SyncAgent.jittered(1), "never less than a second");
    }
}
