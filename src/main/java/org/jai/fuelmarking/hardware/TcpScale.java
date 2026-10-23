package org.jai.fuelmarking.hardware;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A scale (or the simulator) reachable over TCP, streaming {@link ScaleLineProtocol} lines. Connects on first use and
 * reconnects by itself if the connection drops, so restarting the simulator needs no restart of the application.
 */
public class TcpScale implements Hardware.DigitalScale {
    private final String host;
    private final int port;
    private StreamScale current;

    public TcpScale(String host, int port) {
        this.host = host;
        this.port = port;
    }

    private synchronized StreamScale connection() {
        if (current != null && !current.isClosed()) return current;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 2_000);
            socket.setTcpNoDelay(true);
            current = new StreamScale(name(), socket.getInputStream(), socket.getOutputStream());
            return current;
        } catch (IOException e) {
            current = null;
            throw new Hardware.ScaleException("Cannot connect to the scale at " + host + ":" + port + " - is it (or the simulator) running?");
        }
    }

    @Override
    public Hardware.Reading read() {
        StreamScale c = connection();
        // a fresh connection has no data yet: give the first line a moment to arrive
        long end = System.currentTimeMillis() + 1_500;
        while (true) {
            try {
                return c.read();
            } catch (Hardware.ScaleException e) {
                if (System.currentTimeMillis() >= end || c.isClosed()) throw e;
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    @Override
    public void tare() { connection().tare(); }

    @Override
    public String name() { return "TCP " + host + ":" + port; }
}
