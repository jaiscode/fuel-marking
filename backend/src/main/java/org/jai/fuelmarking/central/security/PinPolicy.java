package org.jai.fuelmarking.central.security;

/** Rules for a PIN chosen by an administrator. */
public final class PinPolicy {
    private PinPolicy() {}

    /** Throws IllegalArgumentException with a user-facing reason if the PIN is unacceptable. */
    public static void check(String pin) {
        if (pin == null || !pin.matches("\\d{4,8}")) throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        if (pin.chars().distinct().count() == 1) throw new IllegalArgumentException("PIN must not repeat a single digit");
        boolean up = true, down = true;
        for (int i = 1; i < pin.length(); i++) {
            up &= pin.charAt(i) == pin.charAt(i - 1) + 1;
            down &= pin.charAt(i) == pin.charAt(i - 1) - 1;
        }
        if (up || down) throw new IllegalArgumentException("PIN must not be a simple sequence");
    }
}
