package org.jai.fuelmarking.ui;

import org.jai.fuelmarking.model.MarkingStatus;
import org.jai.fuelmarking.model.TruckMarking;
import org.jai.fuelmarking.session.Session;

class VerifyTab extends MarkingStepTab {
    VerifyTab() { super("Verify", MarkingStatus.DISPENSED, "Verified - sign"); }

    @Override
    protected void perform(TruckMarking t) {
        byte[] sig = sign("sign to verify marking " + t.getOrderNumber());
        if (sig == null) return;
        service.verify(Session.get().user(), t.getUuid(), sig);
        done("Marking completed.");
    }
}
