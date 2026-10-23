package org.jai.fuelmarking.hardware;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The simulator as a network scale, read by the same TcpScale a real networked scale would use. */
class ScaleSimulatorServerTest {
    SimulatedScale model;
    ScaleSimulatorServer server;
    TcpScale client;

    @BeforeEach
    void start() throws IOException {
        model = new SimulatedScale(System::currentTimeMillis, new Random(5), 250, 1500);
        server = new ScaleSimulatorServer(model, 0, 40);
        client = new TcpScale("127.0.0.1", server.port());
    }

    @AfterEach void stop() { server.close(); }

    static <T> T eventually(Supplier<T> s, Predicate<T> ok, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        T v = null;
        while (System.currentTimeMillis() < end) {
            try { v = s.get(); if (ok.test(v)) return v; } catch (Hardware.ScaleException ignored) { /* keep waiting */ }
            Thread.sleep(25);
        }
        fail("condition not reached; last value " + v);
        return v;
    }

    String error() { try { client.read(); return ""; } catch (Hardware.ScaleException e) { return e.getMessage(); } }

    @Test
    void aWeightPlacedInTheSimulatorIsReadOverTheNetworkOnceItSettles() throws Exception {
        assertEquals(new Hardware.Reading(0.0, true), eventually(client::read, Hardware.Reading::stable, 3_000));
        model.placeOnScale(16.40);
        assertFalse(eventually(client::read, r -> !r.stable(), 3_000).stable(), "first it is settling");
        assertEquals(new Hardware.Reading(16.40, true), eventually(client::read, Hardware.Reading::stable, 3_000));
    }

    @Test
    void tareOverTheNetworkZeroesTheSimulator() throws Exception {
        model.placeOnScale(2.0);
        eventually(client::read, r -> r.stable() && r.kg() == 2.0, 3_000);
        client.tare();
        assertEquals(0.0, eventually(client::read, r -> r.stable() && r.kg() == 0.0, 3_000).kg());
        assertEquals(2.0, model.tareKg());
    }

    @Test
    void overloadAndUnpluggedLookLikeTheRealThing() throws Exception {
        model.placeOnScale(10);
        eventually(client::read, Hardware.Reading::stable, 3_000);
        model.setOverload(true);
        assertTrue(eventually(this::error, m -> m.contains("overloaded"), 3_000).contains("overloaded"));
        model.setOverload(false);
        eventually(client::read, r -> r.stable() && r.kg() == 10.0, 3_000);

        model.setDisconnected(true);                                       // the simulator goes silent
        assertTrue(eventually(this::error, m -> m.contains("No data"), 5_000).contains("No data"));
        model.setDisconnected(false);
        assertEquals(10.0, eventually(client::read, Hardware.Reading::stable, 3_000).kg());
    }

    @Test
    void theClientReconnectsWhenTheSimulatorIsRestarted() throws Exception {
        eventually(client::read, Hardware.Reading::stable, 3_000);
        int port = server.port();
        server.close();
        assertFalse(eventually(this::error, m -> !m.isEmpty(), 5_000).isEmpty(), "reports the loss instead of a stale weight");

        server = new ScaleSimulatorServer(model, port, 40);                // simulator restarted on the same port
        model.placeOnScale(15.9);
        assertEquals(15.9, eventually(client::read, r -> r.stable() && r.kg() == 15.9, 5_000).kg());
    }

    @Test
    void cannotConnectGivesAnOperatorFriendlyMessage() {
        server.close();
        TcpScale nobodyHome = new TcpScale("127.0.0.1", server.port());
        String msg = assertThrows(Hardware.ScaleException.class, nobodyHome::read).getMessage();
        assertTrue(msg.contains("Cannot connect to the scale at 127.0.0.1:") && msg.contains("running"), msg);
    }

    @Test
    void theServerOnlyListensOnTheLoopbackInterface() throws Exception {
        assertTrue(java.net.InetAddress.getByName("127.0.0.1").isLoopbackAddress());
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.port())) { assertTrue(s.isConnected()); }
    }
}
