package org.jai.fuelmarking.central.master;

/** Where master data finally goes. Blocks until the broker has acknowledged the record, or throws. */
public interface MasterDataSink {
    void send(String key, String value) throws Exception;
}
