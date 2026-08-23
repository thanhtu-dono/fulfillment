package com.example.fulfillment.audit;

import com.example.fulfillment.domain.OrderId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AuditTrail {
    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    public void append(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> forOrder(OrderId orderId) {
        return events.stream().filter(event -> orderId.equals(event.orderId())).toList();
    }

    public List<AuditEvent> all() {
        return List.copyOf(events);
    }
}
