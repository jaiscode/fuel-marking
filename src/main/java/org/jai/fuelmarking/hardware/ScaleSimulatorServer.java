package org.jai.fuelmarking.hardware;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a {@link SimulatedScale} look like a network scale: every client gets a {@link ScaleLineProtocol} line several
 * times a second and may send "T" to tare. A disconnected simulated scale goes silent (like a pulled cable), an overloaded
 * one sends "OL". Listens on the loopback interface only, so it cannot be reached from other machines.
 */
public class ScaleSimulatorServer implements AutoCloseable {
    private final SimulatedScale model;
    private final ServerSocket server;
    private final long intervalMillis;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Port 0 picks a free port (see {@link #port()}). */
    public ScaleSimulatorServer(SimulatedScale model, int port, long intervalMillis) throws IOException {
        this.model = model;
        this.intervalMillis = intervalMillis;
        this.server = new ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::acceptLoop, "scale-simulator-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return server.getLocalPort(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                clients.add(s);
                serve(s);
            } catch (IOException e) {
                if (!closed) continue;
            }
        }
    }

    private void serve(Socket s) {
        Thread writer = new Thread(() -> {
            try (OutputStream out = s.getOutputStream()) {
                while (!closed && !s.isClosed()) {
                    String line;
                    try {
                        line = ScaleLineProtocol.format(model.read());
                    } catch (Hardware.ScaleException e) {
                        line = model.isDisconnected() ? null : "OL";   // unplugged: say nothing
                    }
                    if (line != null) {
                        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    Thread.sleep(intervalMillis);
                }
            } catch (IOException | InterruptedException e) {
                // client went away
            } finally {
                drop(s);
            }
        }, "scale-simulator-writer");
        writer.setDaemon(true);
        writer.start();

        Thread commands = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))) {
                String cmd;
                while ((cmd = r.readLine()) != null) {
                    if (ScaleLineProtocol.TARE_COMMAND.equalsIgnoreCase(cmd.strip())) {
                        try { model.tare(); } catch (Hardware.ScaleException ignored) { /* unplugged scales ignore commands */ }
                    }
                }
            } catch (IOException ignored) {
                // client went away
            } finally {
                drop(s);
            }
        }, "scale-simulator-commands");
        commands.setDaemon(true);
        commands.start();
    }

    private void drop(Socket s) {
        clients.remove(s);
        try { s.close(); } catch (IOException ignored) { /* already closed */ }
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { /* shutting down */ }
        clients.forEach(this::drop);
    }
}
