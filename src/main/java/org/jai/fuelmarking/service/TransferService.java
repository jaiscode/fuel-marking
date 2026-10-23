package org.jai.fuelmarking.service;

import org.jai.fuelmarking.config.Tx;
import org.jai.fuelmarking.dao.*;
import org.jai.fuelmarking.model.Records.*;
import org.jai.fuelmarking.model.Role;

import java.util.*;

/** Warehouse -> site movement of sealed containers, weighed on dispatch and again on receipt. */
public class TransferService {
    private final TransferDao transfers = new TransferDao();
    private final ContainerDao containers = new ContainerDao();
    private final SignatureDao signatures = new SignatureDao();

    /**
     * Dispatches the given sealed containers to another site. Containers are not weighed: the scan list is the record, with who sent it and when.
     * A signature is optional (none is asked for on screen); when one is given it is kept.
     */
    public Transfer dispatch(AppUser manager, Site from, Site to, List<String> barcodes, byte[] signature) {
        Guard.require(manager, Role.WAREHOUSE_MANAGER);
        if (from.id().equals(to.id())) throw new IllegalArgumentException("Choose a different destination site");
        if (barcodes.isEmpty()) throw new IllegalArgumentException("Add at least one container");
        if (new HashSet<>(barcodes).size() != barcodes.size()) throw new IllegalArgumentException("A container is listed twice");
        return Tx.call(c -> {
            Transfer t = transfers.insert(c, from.id(), to.id(), manager.id(), "SUPPLY");
            for (String barcode : barcodes) {
                Container k = containers.findByBarcode(c, barcode, true)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
                if (!from.id().equals(k.siteId())) throw new IllegalStateException(k.barcode() + " is not at " + from.name());
                if (!"SEALED".equals(k.status())) throw new IllegalStateException(k.barcode() + " is " + k.status() + ", only SEALED containers can be dispatched");
                transfers.addItem(c, t.id(), k.id());
                containers.setLocation(c, k.id(), from.id(), "IN_TRANSIT");
            }
            transfers.publish(c, t.id());
            if (signature != null) signatures.save(c, "TRANSFER", t.id(), "DISPATCH", manager.id(), signature);
            return t;
        });
    }

    public Transfer dispatch(AppUser manager, Site from, Site to, List<String> barcodes) { return dispatch(manager, from, to, barcodes, null); }

    public Transfer returnEmpties(AppUser supervisor, Site from, Site to, List<String> barcodes) { return returnEmpties(supervisor, from, to, barcodes, null); }

    public void receive(AppUser receiver, Transfer transfer, List<String> barcodes) { receive(receiver, transfer, barcodes, null); }

    /**
     * A marking site sends finished jerricans back to a warehouse for a refill. Only EMPTY jerricans that are at this site can go, and
     * only to a warehouse. They arrive EMPTY, ready to be refilled and sealed.
     */
    public Transfer returnEmpties(AppUser supervisor, Site from, Site to, List<String> barcodes, byte[] signature) {
        Guard.require(supervisor, Role.SUPERVISOR);
        if (from.id().equals(to.id())) throw new IllegalArgumentException("Choose a different destination site");
        if (!"WAREHOUSE".equals(to.siteType())) throw new IllegalArgumentException("Empty jerricans go back to a warehouse - " + to.name() + " is not one");
        if (barcodes.isEmpty()) throw new IllegalArgumentException("Add at least one jerrican");
        if (new HashSet<>(barcodes).size() != barcodes.size()) throw new IllegalArgumentException("A jerrican is listed twice");
        return Tx.call(c -> {
            requireShift(c, supervisor, from.id());
            Transfer t = transfers.insert(c, from.id(), to.id(), supervisor.id(), "RETURN");
            for (String barcode : barcodes) {
                Container k = containers.findByBarcode(c, barcode, true)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
                if (k.type().isBulk()) throw new IllegalArgumentException(k.barcode() + " is an IBC/drum - only jerricans are sent back");
                if (!from.id().equals(k.siteId())) throw new IllegalStateException(k.barcode() + " is not at " + from.name());
                if (!"EMPTY".equals(k.status()))
                    throw new IllegalStateException(k.barcode() + " is " + k.status() + " - declare it empty before sending it back");
                transfers.addItem(c, t.id(), k.id());
                containers.setLocation(c, k.id(), from.id(), "IN_TRANSIT");
            }
            transfers.publish(c, t.id());
            if (signature != null) signatures.save(c, "TRANSFER", t.id(), "DISPATCH", supervisor.id(), signature);
            return t;
        });
    }

    /** A supervisor works with containers only during a shift. */
    private void requireShift(java.sql.Connection c, AppUser user, UUID siteId) throws java.sql.SQLException {
        if (user.role() == Role.SUPERVISOR && new ShiftDao().findOpen(c, siteId).isEmpty()) throw new IllegalStateException("Start a shift first");
    }

    public List<Transfer> incoming(UUID siteId) {
        return Tx.call(c -> transfers.incoming(c, siteId));
    }

    public List<TransferItem> items(UUID transferId) {
        return Tx.call(c -> transfers.items(c, transferId));
    }

    /** Receives the given containers of a transfer at the destination site (all of them, or some now and the rest later). */
    public void receive(AppUser receiver, Transfer transfer, List<String> barcodes, byte[] signature) {
        Guard.require(receiver, Role.SUPERVISOR, Role.WAREHOUSE_MANAGER);
        if (barcodes.isEmpty()) throw new IllegalArgumentException("Select at least one container to receive");
        Tx.run(c -> {
            Transfer t = transfers.find(c, transfer.id(), true).orElseThrow();
            if (!"DISPATCHED".equals(t.status())) throw new IllegalStateException("Transfer already received");
            boolean returned = "RETURN".equals(t.kind());
            if (returned && receiver.role() != Role.WAREHOUSE_MANAGER) throw new IllegalStateException("Returned jerricans are received by the warehouse manager");
            requireShift(c, receiver, t.toSiteId());
            for (String barcode : barcodes) {
                Container k = containers.findByBarcode(c, barcode, true)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown container " + barcode));
                transfers.recordReceipt(c, t.id(), k.id());
                if (returned) {
                    // a returned jerrican is empty and carries no seals, whatever this site's copy of its record still says (it may be from before it left)
                    containers.setVolume(c, k.id(), 0);
                    containers.discardJerricanSeals(c, k.id());
                }
                containers.setLocation(c, k.id(), t.toSiteId(), returned ? "EMPTY" : "SEALED");
            }
            if (transfers.allReceived(c, t.id())) transfers.markReceived(c, t.id(), receiver.id());
            transfers.publish(c, t.id());
            if (signature != null) signatures.save(c, "TRANSFER", t.id(), "RECEIPT", receiver.id(), signature);
            return null;
        });
    }
}
