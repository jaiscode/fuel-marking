package org.jai.fuelmarking.service;

import org.jai.fuelmarking.model.Records.ContainerType;

/** How many seals a container carries is part of its container type (master data): for example plastic jerrican 1, metal jerrican 2. */
public final class Seals {
    private Seals() {}

    public static int required(ContainerType type) { return type.seals(); }

    /**
     * Checks the seal numbers typed for a container of this type and returns them as {top, bottom} (an unused one is null).
     * One seal: the top one only. Two seals: both, and different. No seals required (bulk containers): whatever was typed is kept.
     */
    public static String[] check(ContainerType type, String top, String bottom) {
        String t = top == null ? "" : top.trim(), b = bottom == null ? "" : bottom.trim();
        switch (type.seals()) {
            case 0 -> { return new String[]{t.isEmpty() ? null : t, b.isEmpty() ? null : b}; }
            case 1 -> {
                if (t.isEmpty()) throw new IllegalArgumentException("Enter the seal number: a " + type.name().toLowerCase() + " has one seal");
                if (!b.isEmpty()) throw new IllegalArgumentException("A " + type.name().toLowerCase() + " has only one seal - leave the second seal empty");
                return new String[]{t, null};
            }
            default -> {
                if (t.isEmpty() || b.isEmpty()) throw new IllegalArgumentException("Enter both seal numbers: a " + type.name().toLowerCase() + " has two seals");
                if (t.equals(b)) throw new IllegalArgumentException("The two seals of a " + type.name().toLowerCase() + " must have different numbers");
                return new String[]{t, b};
            }
        }
    }
}
