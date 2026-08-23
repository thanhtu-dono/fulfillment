package com.example.fulfillment.audit;

import com.example.fulfillment.domain.OrderId;

import java.time.Instant;

public record AuditEvent(Instant timestamp, OrderId orderId, AuditEventType type, String message) {
}
