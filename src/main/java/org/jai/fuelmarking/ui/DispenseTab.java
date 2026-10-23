package org.jai.fuelmarking.ui;

import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.session.Session;

class DispenseTab extends MarkingStepTab {
    DispenseTab() { super("Dispense", MarkingStatus.DOSED, "Rum dispensed - sign"); }

    @Override
    protected void perform(TruckMarking t) {
        byte[] sig = sign("sign to confirm rum dispensed into truck " + t.getTruckNumber());
        if (sig == null) return;
        service.dispense(Session.get().user(), t.getUuid(), sig);
        done("Dispensing recorded.");
    }
}
