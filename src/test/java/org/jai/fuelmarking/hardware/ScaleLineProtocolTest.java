package org.jai.fuelmarking.hardware;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScaleLineProtocolTest {
    static Hardware.Reading read(String line) { return ScaleLineProtocol.parse(line).orElseThrow(() -> new AssertionError("not a weight: " + line)); }

    @Test void stableAndUnstableStatusFirstFormat() {
        assertEquals(new Hardware.Reading(16.40, true), read("ST,GS,   16.40 kg"));
        assertEquals(new Hardware.Reading(16.43, false), read("US,GS,   16.43 kg"));
        assertEquals(new Hardware.Reading(-0.02, true), read("ST,NT, -0.02 kg"));
        assertEquals(new Hardware.Reading(1020.0, true), read("st , gs , +001020.00 KG\r"));
    }

    @Test void valueFirstFormatWithTrailingFlag() {
        assertEquals(new Hardware.Reading(16.4, true), read("  16.40 kg  S"));
        assertEquals(new Hardware.Reading(16.4, false), read("16.40 kg ?"));
        assertEquals(new Hardware.Reading(16.4, true), read("16.40 kg"), "no flag is taken as stable");
    }

    @Test void otherUnitsAreConvertedToKilograms() {
        assertEquals(16.4, read("ST,GS,16400 g").kg(), 1e-9);
        assertEquals(10.0, read("ST,GS,22.0462 lb").kg(), 0.001);
    }

    @Test void overloadIsAnErrorNotAWeight() {
        for (String l : List.of("OL", "  O-L ", "-OL", "^^^^^^"))
            assertEquals("The scale is overloaded - remove the weight.", assertThrows(Hardware.ScaleException.class, () -> ScaleLineProtocol.parse(l)).getMessage(), l);
    }

    @Test void everythingElseIsIgnored() {
        for (String l : List.of("", "   ", "READY", "Ohaus Ranger 3000", "ST,GS,abc kg", "ST,GS,16.40", "16.40 stones", "99999999 kg", "kg"))
            assertEquals(Optional.empty(), ScaleLineProtocol.parse(l), "'" + l + "'");
        assertEquals(Optional.empty(), ScaleLineProtocol.parse(null));
    }

    @Test void whatTheSimulatorSendsIsWhatTheParserReads() {
        for (var r : List.of(new Hardware.Reading(16.40, true), new Hardware.Reading(0.0, true), new Hardware.Reading(-1.25, false), new Hardware.Reading(1020.55, true)))
            assertEquals(r, read(ScaleLineProtocol.format(r)));
        assertEquals("ST,GS,   16400.0 g", ScaleLineProtocol.format(new Hardware.Reading(16.4, true)));
        assertEquals(new Hardware.Reading(0.0998, true), read(ScaleLineProtocol.format(new Hardware.Reading(0.0998, true))), "0.1 g resolution survives the wire");
    }
}
