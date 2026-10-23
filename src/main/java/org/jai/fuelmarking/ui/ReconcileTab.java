package org.jai.fuelmarking.ui;

import javafx.scene.control.*;
import org.jai.fuelmarking.model.Records.SealedCheck;
import org.jai.fuelmarking.model.Records.StockCount;
import org.jai.fuelmarking.model.Records.StockEntry;
import org.jai.fuelmarking.service.ReconcileService;
import org.jai.fuelmarking.session.Session;

import java.util.List;

/**
 * The supervisor's reconciliation, on its own screen: weigh the opened jerricans and check the sealed ones against what the system holds, at any
 * time during the shift. The Shift screen shows the figures and has a button that comes here; what has been reconciled this shift is listed below.
 */
class ReconcileTab extends RefreshableTab {
    private final ReconcileService service = new ReconcileService();
    private final ReconcilePanel panel;
    private final TableView<StockCount> history = new TableView<>();

    ReconcileTab(Runnable backToShift) {
        super("Reconcile");
        panel = new ReconcilePanel(this::sign, backToShift);
        history.getColumns().add(Ui.col("When", (StockCount c) -> c.countedAt().toLocalDate() + " " + c.countedAt().toLocalTime().withSecond(0)));
        history.getColumns().add(Ui.col("System (kg)", (StockCount c) -> Ui.kg(c.totalSystemG())));
        history.getColumns().add(Ui.col("Counted (kg)", (StockCount c) -> Ui.kg(c.totalCountedG())));
        history.getColumns().add(Ui.col("Difference (kg)", (StockCount c) -> StockCountTab.signed(c.totalVarianceG())));
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setPlaceholder(new Label("Nothing reconciled yet this shift"));
        history.setPrefHeight(170);
        setContent(new ScrollPane(Ui.page(Ui.header("Reconcile", "Weigh the opened jerricans and check the sealed ones against what the system holds. Do it any time during the shift."),
                panel, Ui.section("Reconciled this shift"), history)) {{ setFitToWidth(true); }});
    }

    /** Called when the supervisor presses sign: record it, raising an incident for every shortfall and every jerrican that is not found. */
    private void sign(List<StockEntry> entries, List<SealedCheck> sealedChecks) {
        Session s = Session.get();
        if (!Ui.confirm("Stock reconciliation\n\n" + panel.summary(entries, sealedChecks) + "\n\nSign to record it?")) return;
        byte[] sig = Ui.signaturePad().capture(s.user().fullName() + " - sign the stock reconciliation");
        if (sig == null) return;
        var count = service.submit(s.user(), s.site(), s.shift(), entries, sealedChecks, null, sig);
        int incidents = (int) service.lines(count.id()).stream().filter(l -> l.incidentId() != null).count();
        refresh();
        Ui.info("Reconciliation recorded" + (incidents > 0 ? ". " + incidents + " incident(s) raised and the system stock corrected." : " - everything matches."));
    }

    @Override
    void refresh() {
        panel.open();
        Session s = Session.get();
        history.getItems().setAll(s.shift() == null ? List.of() : service.history(s.site().id()).stream().filter(c -> s.shift().id().equals(c.shiftId())).toList());
    }
}
