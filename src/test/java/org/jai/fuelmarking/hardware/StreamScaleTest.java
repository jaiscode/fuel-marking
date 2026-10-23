package org.jai.fuelmarking.hardware;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The class a real serial-port scale will be plugged into, exercised with in-memory pipes. */
class StreamScaleTest {
    final AtomicLong now = new AtomicLong(0);
    final PipedOutputStream device = new PipedOutputStream();          // what the scale sends
    final ByteArrayOutputStream toDevice = new ByteArrayOutputStream(); // what we send to the scale
    StreamScale scale;

    StreamScale open() throws IOException {
        scale = new StreamScale("test", new PipedInputStream(device), toDevice, now::get, 2_000);
        return scale;
    }

    @AfterEach void close() { if (scale != null) scale.close(); }

    void send(String line) throws IOException { device.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII)); device.flush(); }

    static <T> T eventually(Supplier<T> s, java.util.function.Predicate<T> ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 3_000;
        T v = null;
        while (System.currentTimeMillis() < end) {
            try { v = s.get(); if (ok.test(v)) return v; } catch (Hardware.ScaleException ignored) { /* keep waiting */ }
            Thread.sleep(10);
        }
        fail("condition not reached; last value " + v);
        return v;
    }

    @Test
    void latestReadingIsReturnedAndUpdates() throws Exception {
        open();
        send("US,GS,   16.43 kg");
        assertEquals(new Hardware.Reading(16.43, false), eventually(scale::read, r -> r.kg() == 16.43));
        send("ST,GS,   16.40 kg");
        assertEquals(new Hardware.Reading(16.40, true), eventually(scale::read, Hardware.Reading::stable));
    }

    @Test
    void noiseBetweenReadingsIsIgnored() throws Exception {
        open();
        send("");
        send("Ohaus Ranger 3000 v1.2");
        send("ST,GS,   16.40 kg");
        send("garbage ###");
        assertEquals(16.40, eventually(scale::read, r -> r.kg() == 16.40).kg());
    }

    @Test
    void aSilentScaleIsAnErrorNotAStaleWeight() throws Exception {
        open();
        assertTrue(assertThrows(Hardware.ScaleException.class, scale::read).getMessage().contains("No data"), "nothing received yet");
        send("ST,GS,   16.40 kg");
        eventually(scale::read, r -> r.kg() == 16.40);
        now.addAndGet(1_900);
        assertEquals(16.40, scale.read().kg(), "still fresh");
        now.addAndGet(200);                                              // 2.1 s without a line: cable pulled, scale off
        assertTrue(assertThrows(Hardware.ScaleException.class, scale::read).getMessage().contains("No data"));
        send("ST,GS,   16.40 kg");                                         // it comes back
        eventually(scale::read, r -> r.kg() == 16.40);
    }

    @Test
    void overloadIsReportedAndClearsWhenTheWeightIsRemoved() throws Exception {
        open();
        send("OL");
        assertTrue(eventually(() -> { try { scale.read(); return ""; } catch (Hardware.ScaleException e) { return e.getMessage(); } }, m -> m.contains("overloaded")).contains("overloaded"));
        send("ST,GS,    0.00 kg");
        assertEquals(0.0, eventually(scale::read, Hardware.Reading::stable).kg());
    }

    @Test
    void tareSendsTheCommandToTheDevice() throws Exception {
        open();
        scale.tare();
        assertEquals("T\r\n", toDevice.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void aClosedLinkNeverReturnsTheLastWeight() throws Exception {
        open();
        send("ST,GS,   16.40 kg");
        eventually(scale::read, r -> r.kg() == 16.40);
        device.close();                                                   // the device end vanishes
        assertTrue(eventually(() -> { try { scale.read(); return ""; } catch (Hardware.ScaleException e) { return e.getMessage(); } }, m -> m.contains("lost")).contains("lost"));
        assertThrows(Hardware.ScaleException.class, scale::tare);
        assertTrue(scale.isClosed());
    }
}
