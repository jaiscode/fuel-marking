package org.jai.fuelmarking;

import org.flywaydb.core.Flyway;
import org.jai.fuelmarking.config.DatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Things that differ between operating systems and must not matter. */
class PortabilityIT {
    @TempDir Path dir;

    /**
     * A Windows checkout usually turns the SQL migrations into CRLF files (git autocrlf). Flyway must compute the same checksum,
     * otherwise a jar built on Windows would refuse to start against a database a Linux/macOS build had migrated.
     */
    @Test
    void migrationChecksumsDoNotDependOnLineEndings() throws Exception {
        var ds = DatabaseConfig.getDataSource();                           // migrates from the classpath (LF files)
        Path source = Path.of(DatabaseConfig.class.getResource("/db/migration").toURI());
        int converted = 0;
        try (Stream<Path> files = Files.list(source)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".sql")).toList()) {
                String lf = Files.readString(f).replace("\r\n", "\n");
                Files.writeString(dir.resolve(f.getFileName()), lf.replace("\n", "\r\n"));
                converted++;
            }
        }
        assertTrue(converted >= 10, "converted the migrations: " + converted);
        assertTrue(Files.readString(dir.resolve("V1__Create_initail_tables.sql")).contains("\r\n"), "the copies really have Windows line endings");
        Flyway.configure().dataSource(ds).locations("filesystem:" + dir.toAbsolutePath()).load().validate();   // throws on any checksum mismatch
    }

    /** The control for the test above: a real change to a migration IS caught, so the line-ending test cannot pass by accident. */
    @Test
    void aRealChangeToAMigrationIsStillCaught() throws Exception {
        var ds = DatabaseConfig.getDataSource();
        Path source = Path.of(DatabaseConfig.class.getResource("/db/migration").toURI());
        try (Stream<Path> files = Files.list(source)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".sql")).toList())
                Files.writeString(dir.resolve(f.getFileName()), Files.readString(f).replace("\n", "\r\n"));
        }
        Path v2 = dir.resolve("V2__Create_indexes.sql");
        Files.writeString(v2, Files.readString(v2) + "\r\n-- tampered\r\nCREATE INDEX tampered ON truck_markings(id);\r\n");
        var e = assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,
                () -> Flyway.configure().dataSource(ds).locations("filesystem:" + dir.toAbsolutePath()).load().validate());
        assertTrue(e.getMessage().toLowerCase().contains("checksum"), e.getMessage());
    }
}
