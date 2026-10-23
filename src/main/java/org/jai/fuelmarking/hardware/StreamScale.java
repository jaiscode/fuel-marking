package org.jai.fuelmarking.hardware;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * A scale on any byte stream that carries {@link ScaleLineProtocol} lines: a serial port, a TCP socket, a test pipe.
 * <p>
 * <b>Adding the real device:</b> open its serial port with your serial library of choice and hand its
 * {@code getInputStream()} / {@code getOutputStream()} to this class; nothing else is needed unless the device's line
 * format differs (then adjust {@link ScaleLineProtocol#parse}).
 * <p>
 * A background thread keeps the latest reading; {@link #read()} returns it, or throws if the device has gone quiet
 * (cable pulled, scale switched off) so the operator is never shown a stale weight.
 */
public class StreamScale implements Hardware.DigitalScale, AutoCloseable {
    private record Snapshot(Hardware.Reading reading, Hardware.ScaleException error, long at) {}

    private final String name;
    private final OutputStream out;
    private final InputStream in;
    private final LongSupplier clock;
    private final long staleMillis;
    private final Thread reader;
    private volatile Snapshot latest;
    private volatile boolean closed;

    public StreamScale(String name, InputStream in, OutputStream out) { this(name, in, out, System::currentTimeMillis, 2_000); }

    public StreamScale(String name, InputStream in, OutputStream out, LongSupplier clock, long staleMillis) {
        this.name = name;
        this.in = in;
        this.out = out;
        this.clock = clock;
        this.staleMillis = staleMillis;
        this.reader = new Thread(this::readLoop, "scale-reader-" + name);
        this.reader.setDaemon(true);
        this.reader.start();
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while (!closed && (line = r.readLine()) != null) {
                try {
                    var parsed = ScaleLineProtocol.parse(line);
                    if (parsed.isPresent()) latest = new Snapshot(parsed.get(), null, clock.getAsLong());
                } catch (Hardware.ScaleException overload) {
                    latest = new Snapshot(null, overload, clock.getAsLong());
                }
            }
        } catch (IOException ignored) {
            // closing the stream ends the loop
        } finally {
            closed = true;
        }
    }

    @Override
    public Hardware.Reading read() {
        Snapshot s = latest;
        if (closed) throw new Hardware.ScaleException("The connection to the scale (" + name + ") was lost.");  // never show the last weight of a dead link
        if (s == null || clock.getAsLong() - s.at() > staleMillis)
            throw new Hardware.ScaleException("No data from the scale (" + name + ") - check that it is switched on and connected.");
        if (s.error() != null) throw s.error();
        return s.reading();
    }

    @Override
    public void tare() {
        if (closed) throw new Hardware.ScaleException("The connection to the scale (" + name + ") was lost.");
        try {
            out.write((ScaleLineProtocol.TARE_COMMAND + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            closed = true;
            throw new Hardware.ScaleException("Could not send the tare command to the scale (" + name + "): " + e.getMessage());
        }
    }

    public boolean isClosed() { return closed; }

    @Override
    public String name() { return name; }

    @Override
    public void close() {
        closed = true;
        try { in.close(); } catch (IOException ignored) { /* already gone */ }
        try { out.close(); } catch (IOException ignored) { /* already gone */ }
        reader.interrupt();
    }
}
