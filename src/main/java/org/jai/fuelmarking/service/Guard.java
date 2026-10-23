package org.jai.fuelmarking.service;

import org.jai.fuelmarking.model.Records.AppUser;
import org.jai.fuelmarking.model.Role;

import java.util.Arrays;

final class Guard {
    private Guard() {}

    static void require(AppUser user, Role... roles) {
        if (user == null || !Arrays.asList(roles).contains(user.role()))
            throw new IllegalStateException("Action not permitted for role " + (user == null ? "none" : user.role()));
    }

    static void signed(byte[] signature) {
        if (signature == null || signature.length == 0) throw new IllegalArgumentException("Signature is required");
    }
}
