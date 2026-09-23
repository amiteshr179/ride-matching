package com.ridematching.billing;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
public class InvoiceController {

    public record Invoice(UUID tripId, String riderId, String driverId, double distanceKm,
                          long durationSeconds, BigDecimal amount, String currency) {
    }

    private final JdbcClient jdbc;

    public InvoiceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/invoices/{tripId}")
    public ResponseEntity<Invoice> get(@PathVariable UUID tripId) {
        return jdbc.sql("""
                        select trip_id, rider_id, driver_id, distance_km, duration_seconds, amount, currency
                        from invoices where trip_id = ?
                        """)
                .param(tripId)
                .query(Invoice.class)
                .optional()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
