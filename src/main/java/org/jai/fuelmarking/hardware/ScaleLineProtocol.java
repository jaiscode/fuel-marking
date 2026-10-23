package org.jai.fuelmarking.hardware;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text lines scales stream over RS-232 / USB-serial / TCP. Understands the common shapes:
 * <pre>
 *   ST,GS,   16.40 kg      stable, gross        (A&amp;D / Ohaus style; US = unstable, NT = net)
 *     16.40 kg  S           trailing S = stable, ? = unstable
 *     16.40 kg              no flag: taken as stable
 *   OL   or   ^^^^^^        overload
 * </pre>
 * Units kg, g and lb are converted to kilograms. Anything else is ignored (headers, blank lines, echoes).
 * When the real device is chosen, adjust {@link #parse} to its exact format; everything else stays the same.
 */
public final class ScaleLineProtocol {
    private static final Pattern STATUS_FIRST = Pattern.compile(
            "^(ST|US)\\s*,\\s*(?:GS|NT|TR)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(kg|g|lb)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_FIRST = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)\\s*(kg|g|lb)\\s*([SM?])?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERLOAD = Pattern.compile("^(?:[+-]?O-?L|\\^{3,})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final double MAX_PLAUSIBLE_KG = 100_000;

    private ScaleLineProtocol() {}

    /** The reading on this line, or empty if the line is not a weight. Throws if the scale reports an overload. */
    public static Optional<Hardware.Reading> parse(String line) {
        if (line == null) return Optional.empty();
        String s = line.strip();
        if (s.isEmpty()) return Optional.empty();
        if (OVERLOAD.matcher(s).matches()) throw new Hardware.ScaleException("The scale is overloaded - remove the weight.");

        Matcher m = STATUS_FIRST.matcher(s);
        if (m.matches()) return reading(m.group(2), m.group(3), m.group(1).equalsIgnoreCase("ST"));
        m = VALUE_FIRST.matcher(s);
        if (m.matches()) return reading(m.group(1), m.group(2), !"?".equals(m.group(3)));
        return Optional.empty();
    }

    private static Optional<Hardware.Reading> reading(String number, String unit, boolean stable) {
        double v = Double.parseDouble(number);
        double kg = switch (unit.toLowerCase(Locale.ROOT)) {
            case "g" -> v / 1000.0;
            case "lb" -> v * 0.45359237;
            default -> v;
        };
        if (Double.isNaN(kg) || Math.abs(kg) > MAX_PLAUSIBLE_KG) return Optional.empty();
        return Optional.of(new Hardware.Reading(Math.round(kg * 1_000_000.0) / 1_000_000.0, stable)); // keep sub-gram resolution
    }

    /** The line a scale (or the simulator) sends for this reading: grams to 0.1 g, which dosing needs. */
    public static String format(Hardware.Reading r) {
        return String.format(Locale.ROOT, "%s,GS,%10.1f g", r.stable() ? "ST" : "US", r.grams());
    }

    /** Command that asks the scale to tare. */
    public static final String TARE_COMMAND = "T";
}
