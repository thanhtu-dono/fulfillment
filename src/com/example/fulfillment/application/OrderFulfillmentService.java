package com.example.fulfillment.application;

import com.example.fulfillment.audit.AuditEvent;
import com.example.fulfillment.audit.AuditEventType;
import com.example.fulfillment.audit.AuditTrail;
import com.example.fulfillment.backorder.BackorderService;
import com.example.fulfillment.domain.DeadLetterLine;
import com.example.fulfillment.domain.FulfillmentResult;
import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.OrderId;
import com.example.fulfillment.domain.OrderLine;
import com.example.fulfillment.domain.OrderStatus;
import com.example.fulfillment.inventory.InventoryRepository;
import com.example.fulfillment.inventory.ReservationAttempt;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public final class OrderFulfillmentService implements AutoCloseable {
    private final InventoryRepository inventory;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final BackorderService backorders;
    private final Map<OrderId, OrderStatus> statuses = new ConcurrentHashMap<>();
    private final java.util.Set<OrderId> acceptedOrderIds = ConcurrentHashMap.newKeySet();
    private final DoubleAdder revenue = new DoubleAdder();
    private final LongAdder shipped = new LongAdder();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();

    public OrderFulfillmentService(InventoryRepository inventory, AuditTrail auditTrail,
                                   Clock clock, double timeScale) {
        this.inventory = inventory;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.backorders = new BackorderService(clock, timeScale, order -> submitInternal(order, false),
            order -> audit(order, AuditEventType.ORDER_ESCALATED,
                "Standard order escalated to priority"));
        this.backorders.start();
    }

    public FulfillmentResult submit(Order order) {
        return submitInternal(order, true);
    }

    private FulfillmentResult submitInternal(Order order, boolean initialSubmission) {
        if (acceptedOrderIds.add(order.id())) {
            submitted.increment();
        }
        if (initialSubmission) {
            statuses.put(order.id(), OrderStatus.RECEIVED);
            audit(order, AuditEventType.ORDER_ACCEPTED, "Order accepted");
        }
        ReservationAttempt attempt = inventory.tryReserve(order);
        if (attempt.reserved()) {
            double orderRevenue = attempt.allocations().stream()
                    .mapToDouble(allocation -> allocation.line().quantity() * allocation.unitPrice()).sum();
            revenue.add(orderRevenue);
            shipped.increment();
            statuses.put(order.id(), OrderStatus.SHIPPED);
            audit(order, AuditEventType.RESERVATION_SUCCEEDED, "Reservation succeeded");
            audit(order, AuditEventType.ORDER_SHIPPED, "Order shipped");
            return new FulfillmentResult(OrderStatus.SHIPPED, attempt.allocations(), List.of(), List.of());
        }
        if (!order.partialAllowed()) {
            for (OrderLine line : order.lines()) {
                if (line.quantity() > inventory.companyCapacity(line.sku())) {
                    statuses.put(order.id(), OrderStatus.DEAD_LETTERED);
                    deadLettered.increment();
                    audit(order, AuditEventType.ORDER_DEAD_LETTERED, "Quantity exceeds company capacity");
                    return new FulfillmentResult(OrderStatus.DEAD_LETTERED, List.of(), List.of(),
                            order.lines().stream().map(item -> new DeadLetterLine(item,
                                    "EXCEEDS_COMPANY_CAPACITY", Instant.now(clock))).toList());
                }
            }
            statuses.put(order.id(), OrderStatus.BACKORDERED);
            backorders.enqueue(order);
            audit(order, AuditEventType.RESERVATION_ROLLED_BACK, "Reservation rolled back");
            audit(order, AuditEventType.ORDER_BACKORDERED, "Order backordered");
            return new FulfillmentResult(OrderStatus.BACKORDERED, List.of(), order.lines(), List.of());
        }
        return processPartial(order);
    }

    private FulfillmentResult processPartial(Order order) {
        var allocations = new java.util.ArrayList<com.example.fulfillment.domain.ReservationAllocation>();
        var pending = new java.util.ArrayList<OrderLine>();
        var dead = new java.util.ArrayList<DeadLetterLine>();
        for (OrderLine line : order.lines()) {
            ReservationAttempt attempt = inventory.tryReserve(new Order(order.id(), order.tier(), false,
                    List.of(line), order.submittedAt(), order.ingestionSequence()));
            if (attempt.reserved()) {
                allocations.addAll(attempt.allocations());
                revenue.add(line.quantity() * attempt.allocations().get(0).unitPrice());
            } else if (line.quantity() > inventory.companyCapacity(line.sku())) {
                dead.add(new DeadLetterLine(line, "EXCEEDS_COMPANY_CAPACITY", Instant.now(clock)));
                deadLettered.increment();
            } else {
                pending.add(line);
            }
        }
        if (!pending.isEmpty()) {
            backorders.enqueue(new Order(order.id(), order.tier(), true, pending,
                    order.submittedAt(), order.ingestionSequence()));
            statuses.put(order.id(), OrderStatus.BACKORDERED);
            audit(order, AuditEventType.ORDER_BACKORDERED, "Some lines backordered");
        } else {
            statuses.put(order.id(), allocations.isEmpty() ? OrderStatus.DEAD_LETTERED : OrderStatus.SHIPPED);
        }
        if (!allocations.isEmpty()) {
            shipped.increment();
            audit(order, AuditEventType.ORDER_SHIPPED, "Partial order shipped");
        }
        return new FulfillmentResult(statuses.get(order.id()), allocations, pending, dead);
    }

    public void restock(com.example.fulfillment.domain.Sku sku,
                        com.example.fulfillment.domain.FulfillmentCenter center, int quantity) {
        inventory.restock(sku, center, quantity);
        auditTrail.append(new AuditEvent(Instant.now(clock), null, AuditEventType.RESTOCK_APPLIED,
                sku + " at " + center + " +" + quantity));
        backorders.signalRestock();
        backorders.processNow();
    }

    public InventoryRepository inventory() { return inventory; }
    public AuditTrail auditTrail() { return auditTrail; }
    public int backorderCount() { return backorders.size(); }
    public long deadLetterCount() { return deadLettered.sum(); }
    public double revenue() { return revenue.sum(); }
    public long shippedCount() { return shipped.sum(); }
    public long submittedCount() { return submitted.sum(); }
        public int escalateBackorders() { return backorders.escalateEligible(order -> audit(order,
            AuditEventType.ORDER_ESCALATED, "Standard order escalated to priority")); }
    public OrderStatus status(OrderId orderId) { return statuses.get(orderId); }

    private void audit(Order order, AuditEventType type, String message) {
        auditTrail.append(new AuditEvent(Instant.now(clock), order.id(), type, message));
    }

    @Override
    public void close() {
        backorders.close();
    }
}
