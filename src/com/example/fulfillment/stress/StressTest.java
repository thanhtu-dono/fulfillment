package com.example.fulfillment.stress;

import com.example.fulfillment.application.OrderFulfillmentService;
import com.example.fulfillment.audit.AuditEventType;
import com.example.fulfillment.audit.AuditTrail;
import com.example.fulfillment.domain.FulfillmentCenter;
import com.example.fulfillment.domain.InventoryKey;
import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.OrderId;
import com.example.fulfillment.domain.OrderLine;
import com.example.fulfillment.domain.OrderTier;
import com.example.fulfillment.domain.Sku;
import com.example.fulfillment.inventory.InventoryRepository;
import com.example.fulfillment.protocol.InventorySeedReader.InventorySeed;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.stream.Collectors;

public final class StressTest {
    private static final int THREADS = 8;
    private static final int ORDERS = 5_000;
    private static final Sku SKU = new Sku("SKU-STRESS");

    private StressTest() {
    }

    public static void main(String[] args) throws InterruptedException {
        Map<InventoryKey, InventorySeed> seed = Map.of(
            new InventoryKey(SKU, FulfillmentCenter.FC_EAST), new InventorySeed(51, 10.0),
            new InventoryKey(SKU, FulfillmentCenter.FC_WEST), new InventorySeed(49, 10.0),
            new InventoryKey(SKU, FulfillmentCenter.FC_CENTRAL), new InventorySeed(0, 10.0));
        InventoryRepository inventory = InventoryRepository.fromSeed(seed);
        AuditTrail audit = new AuditTrail();
        long started = System.nanoTime();
        try (OrderFulfillmentService service = new OrderFulfillmentService(inventory, audit,
                Clock.systemUTC(), 1_000.0)) {
            ExecutorService producers = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(THREADS);
            for (int thread = 0; thread < THREADS; thread++) {
                int worker = thread;
                producers.submit(() -> {
                    try {
                        start.await();
                        for (int index = worker; index < ORDERS; index += THREADS) {
                            OrderTier tier = index < 101 ? OrderTier.PRIORITY : OrderTier.STANDARD;
                            service.submit(new Order(new OrderId(String.format("ORD-%06d", index + 1)), tier,
                                    false, List.of(new OrderLine(SKU, 2)), Instant.now(), index));
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            boolean completed = finished.await(30, TimeUnit.SECONDS);
            producers.shutdownNow();
            int escalations = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                escalations += service.escalateBackorders();
                Thread.sleep(10);
            }
            long reserved = service.shippedCount();
            boolean nonNegative = inventory.snapshot().values().stream().allMatch(stock -> stock.quantity() >= 0);
            long successfulAuditReservations = audit.all().stream()
                    .filter(event -> event.type() == AuditEventType.RESERVATION_SUCCEEDED).count();
            boolean noDuplicateReservation = successfulAuditReservations == reserved;
            boolean progress = completed && service.submittedCount() == ORDERS;
                Set<String> escalationIds = audit.all().stream()
                    .filter(event -> event.type() == AuditEventType.ORDER_ESCALATED)
                    .map(event -> event.orderId().value())
                    .collect(Collectors.toSet());
                long escalationEvents = audit.all().stream()
                    .filter(event -> event.type() == AuditEventType.ORDER_ESCALATED).count();
                    Set<String> standardBackorderIds = audit.all().stream()
                        .filter(event -> event.type() == AuditEventType.ORDER_BACKORDERED
                            && event.orderId().value().compareTo("ORD-000102") >= 0)
                        .map(event -> event.orderId().value())
                        .collect(Collectors.toSet());
                    boolean escalationExactlyOnce = escalationEvents == escalationIds.size()
                        && !escalationIds.isEmpty() && escalationIds.equals(standardBackorderIds);
            System.out.println("STRESS-TEST");
            System.out.println("orders=" + ORDERS + " threads=" + THREADS + " demand=10000 startingStock=100");
            System.out.println("PASS reservedWithinStock=" + (reserved <= 100));
            System.out.println("PASS noDuplicateReservation=" + noDuplicateReservation);
            System.out.println("PASS liveness=" + progress);
            System.out.println("PASS escalationExactlyOnce=" + escalationExactlyOnce);
            System.out.println("escalations=" + escalations + " reserved=" + reserved
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " stockNonNegative=" + nonNegative);
        }
    }
}
