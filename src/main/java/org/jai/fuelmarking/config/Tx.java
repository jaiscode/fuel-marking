package org.jai.fuelmarking.config;

import java.sql.Connection;
import java.sql.SQLException;

/** Runs a unit of work in a single DB transaction. */
public final class Tx {
    private Tx() {}

    @FunctionalInterface
    public interface Work<T> { T run(Connection c) throws SQLException; }

    public static <T> T call(Work<T> work) {
        try (Connection c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public static void run(Work<Void> work) { call(work); }
}
