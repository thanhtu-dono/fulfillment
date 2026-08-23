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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public final class OrderFulfillmentService implements AutoCloseable {
    private final InventoryRepository inventory;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final BackorderService backorders;
    private final Map<OrderId, OrderStatus> statuses = new ConcurrentHashMap<>();
    private final Map<OrderId, CompletableFuture<OrderStatus>> statusCompletion = new ConcurrentHashMap<>();
    private final java.util.Set<OrderId> acceptedOrderIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<OrderId> shippedOrderIds = ConcurrentHashMap.newKeySet();
    private final Map<OrderId, FulfillmentResult> completedResults = new ConcurrentHashMap<>();
    private final Map<OrderId, CompletableFuture<FulfillmentResult>> submissions = new ConcurrentHashMap<>();
    private final DoubleAdder revenue = new DoubleAdder();
    private final LongAdder shipped = new LongAdder();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();

    public OrderFulfillmentService(InventoryRepository inventory, AuditTrail auditTrail,
                                   Clock clock, double timeScale) {
        this.inventory = inventory;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.backorders = new BackorderService(clock, timeScale,
            entry -> submitInternal(entry.order(), false, entry.enqueuedAt()),
            order -> audit(order, AuditEventType.ORDER_ESCALATED,
                "Standard order escalated to priority"));
        this.backorders.start();
    }

    public FulfillmentResult submit(Order order) {
        FulfillmentResult completed = completedResults.get(order.id());
        if (completed != null) {
            return completed;
        }
        CompletableFuture<FulfillmentResult> ownSubmission = new CompletableFuture<>();
        CompletableFuture<FulfillmentResult> active = submissions.putIfAbsent(order.id(), ownSubmission);
        if (active != null) {
            return active.join();
        }
        try {
            FulfillmentResult result = submitInternal(order, true, Instant.now(clock));
            completedResults.put(order.id(), result);
            ownSubmission.complete(result);
            return result;
        } catch (RuntimeException exception) {
            ownSubmission.completeExceptionally(exception);
            throw exception;
        } finally {
            submissions.remove(order.id(), ownSubmission);
        }
    }

    private FulfillmentResult submitInternal(Order order, boolean initialSubmission, Instant enqueuedAt) {
        FulfillmentResult result = processSubmission(order, initialSubmission, enqueuedAt);
        completedResults.put(order.id(), result);
        return result;
    }

    private FulfillmentResult processSubmission(Order order, boolean initialSubmission, Instant enqueuedAt) {
        statusCompletion.put(order.id(), new CompletableFuture<>());
        if (acceptedOrderIds.add(order.id())) {
            submitted.increment();
        }
        if (initialSubmission) {
            statuses.put(order.id(), OrderStatus.RECEIVED);
            audit(order, AuditEventType.ORDER_ACCEPTED, "Order accepted");
        }
        ReservationAttempt attempt = inventory.tryReserve(order, reservation -> {
            if (reservation.reserved()) {
                finishStatus(order.id(), OrderStatus.SHIPPED);
            }
        });
        if (attempt.reserved()) {
            double orderRevenue = attempt.allocations().stream()
                    .mapToDouble(allocation -> allocation.line().quantity() * allocation.unitPrice()).sum();
            revenue.add(orderRevenue);
            recordShipped(order.id());
            audit(order, AuditEventType.RESERVATION_SUCCEEDED, "Reservation succeeded");
            audit(order, AuditEventType.ORDER_SHIPPED, "Order shipped");
            return new FulfillmentResult(OrderStatus.SHIPPED, attempt.allocations(), List.of(), List.of());
        }
        if (!order.partialAllowed()) {
            for (OrderLine line : order.lines()) {
                if (line.quantity() > inventory.companyCapacity(line.sku())) {
                    finishStatus(order.id(), OrderStatus.DEAD_LETTERED);
                    deadLettered.increment();
                    audit(order, AuditEventType.ORDER_DEAD_LETTERED, "Quantity exceeds company capacity");
                    return new FulfillmentResult(OrderStatus.DEAD_LETTERED, List.of(), List.of(),
                            order.lines().stream().map(item -> new DeadLetterLine(item,
                                    "EXCEEDS_COMPANY_CAPACITY", Instant.now(clock))).toList());
                }
            }
            finishStatus(order.id(), OrderStatus.BACKORDERED);
            backorders.enqueue(order, enqueuedAt);
            audit(order, AuditEventType.RESERVATION_ROLLED_BACK, "Reservation rolled back");
            audit(order, AuditEventType.ORDER_BACKORDERED, "Order backordered");
            return new FulfillmentResult(OrderStatus.BACKORDERED, List.of(), order.lines(), List.of());
        }
        return processPartial(order, enqueuedAt);
    }

    private FulfillmentResult processPartial(Order order, Instant originalEnqueuedAt) {
        var allocations = new java.util.ArrayList<com.example.fulfillment.domain.ReservationAllocation>();
        var pending = new java.util.ArrayList<OrderLine>();
        var dead = new java.util.ArrayList<DeadLetterLine>();
        for (OrderLine line : order.lines()) {
                ReservationAttempt attempt = inventory.tryReserve(new Order(order.id(), order.tier(), false,
                    List.of(new OrderLine(line.sku(), line.quantity(), line.lineNumber())),
                    order.submittedAt(), order.ingestionSequence()));
            if (attempt.reserved()) {
                allocations.addAll(attempt.allocations());
                revenue.add(line.quantity() * attempt.allocations().get(0).unitPrice());
            } else if (line.quantity() > inventory.companyCapacity(line.sku())) {
                dead.add(new DeadLetterLine(line, "EXCEEDS_COMPANY_CAPACITY", Instant.now(clock)));
                deadLettered.increment();
                audit(order, AuditEventType.ORDER_DEAD_LETTERED,
                        "Line exceeds company capacity: " + line.sku());
            } else {
                pending.add(line);
            }
        }
        if (!pending.isEmpty()) {
                backorders.enqueue(new Order(order.id(), order.tier(), true, pending,
                        order.submittedAt(), order.ingestionSequence()), originalEnqueuedAt);
                finishStatus(order.id(), allocations.isEmpty()
                    ? OrderStatus.BACKORDERED : OrderStatus.PARTIALLY_SHIPPED);
            audit(order, AuditEventType.ORDER_BACKORDERED, "Some lines backordered");
        } else {
            finishStatus(order.id(), allocations.isEmpty() ? OrderStatus.DEAD_LETTERED : OrderStatus.SHIPPED);
        }
        if (!allocations.isEmpty()) {
            recordShipped(order.id());
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
    public OrderStatus status(OrderId orderId) {
        CompletableFuture<OrderStatus> completion = statusCompletion.get(orderId);
        if (completion != null) {
            completion.join();
        }
        return statuses.get(orderId);
    }

    private void finishStatus(OrderId orderId, OrderStatus status) {
        statuses.put(orderId, status);
        CompletableFuture<OrderStatus> completion = statusCompletion.get(orderId);
        if (completion != null) {
            completion.complete(status);
        }
    }

    private void recordShipped(OrderId orderId) {
        if (shippedOrderIds.add(orderId)) {
            shipped.increment();
        }
    }

    private void audit(Order order, AuditEventType type, String message) {
        auditTrail.append(new AuditEvent(Instant.now(clock), order.id(), type, message));
    }

    @Override
    public void close() {
        backorders.close();
    }
}
