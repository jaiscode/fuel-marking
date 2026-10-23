package org.jai.fuelmarking.central.sync;

import org.jai.fuelmarking.central.sync.SyncDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final SyncService service;

    public SyncController(SyncService service) { this.service = service; }

    @PostMapping("/push")
    PushResponse push(Authentication auth, @RequestBody PushRequest request) {
        return service.push((UUID) auth.getPrincipal(), request.events());
    }

    @GetMapping("/pull")
    PullResponse pull(Authentication auth, @RequestParam(defaultValue = "0") long cursor) {
        return service.pull((UUID) auth.getPrincipal(), cursor);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
