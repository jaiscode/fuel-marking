---
name: backend-it-environment
description: How to run fuel-marking backend ITs locally (throwaway PG16 on 55432, Java 21) and the gotchas hit doing so
metadata:
  type: project
---

Backend ITs (`cd backend && mvn test -Dtest='*IT,*Test'`) need PostgreSQL on 127.0.0.1:55432 with database `fuel_central_test`
(user postgres, any password with trust auth). Kafka is embedded (`@EmbeddedKafka`), so MasterDataOutboxIT runs without a broker.

- Use Java 21 (`JAVA_HOME=/Users/jai.raghuram/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home`); the shell default is 11.
- PG16 is at `/opt/homebrew/opt/postgresql@16/bin`. When the data dir is under the long session scratchpad path, start with
  `-c unix_socket_directories=''` (the socket path exceeds 103 bytes and the server refuses to start).
- Never touch `fuel-marking/.dev` (the user's dev PostgreSQL 15 data).

**Why:** none of this is in the README, and the first start attempt failed on the socket path.
**How to apply:** set up a throwaway cluster this way for any backend IT run, and stop it afterwards.

Design decision (2026-10-29, branch fix/transfer-pull-ordering): `synced_entities.seq` is assigned AFTER commit by
`SyncSequencer` (TRANSFER rows only). The team explicitly rejected locking the push path (it would serialise all sites'
pushes). Custody conflicts are accepted and flagged (`SYNC_CONFLICT` audit rows), never rejected, because sites are
offline-first; the check runs after the push commits and is fail-open (a security review required that: an error inside
the push tx made a site retry forever). Changing an unreleased migration means dropping/recreating `fuel_central_test`.
