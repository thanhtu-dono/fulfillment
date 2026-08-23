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
import com.example.fulfillment.protocol.Checksum;
import com.example.fulfillment.protocol.OrderFeedParser;
import com.example.fulfillment.protocol.ParsedOrder;
import com.example.fulfillment.protocol.RejectRecord;
import com.example.fulfillment.protocol.RejectWriter;
import com.example.fulfillment.protocol.InventorySeedReader.InventorySeed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CoreBehaviorTest {
    private static final Sku SKU = new Sku("SKU-TEST");

    private CoreBehaviorTest() {
    }

    public static void main(String[] args) throws Exception {
        parserChecks();
        duplicateLineRollbackCheck();
        partialAndRestockCheck();
        escalationAuditCheck();
        System.out.println("CORE_BEHAVIOR_TEST_PASS");
    }

    private static void parserChecks() throws Exception {
        Path rejects = Files.createTempFile("rejects", ".log");
        List<RejectRecord> records = new ArrayList<>();
        try (RejectWriter writer = new RejectWriter(rejects)) {
            OrderFeedParser parser = new OrderFeedParser(Set.of(SKU), writer,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), message -> require(
                        message.startsWith("WARNING:"), "reserved flag warning"));
            List<ParsedOrder> orders = new ArrayList<>();
            parser.parseConcurrently(List.of(
                    withChecksum("O|ORD-000001|STD|PX|SKU-TESTx1"),
                    withChecksum("C|ORD-000001|SKU-TESTx2"),
                    withChecksum("O|ORD-000001|STD|--|SKU-TESTx1"),
                    withChecksum("O|ORD-000002|STD|--|SKU-UNKNOWNx1")), 4, orders::add);
            require(orders.size() == 1, "one valid logical order");
            require(orders.get(0).order().lines().size() == 2, "continuation parsed");
            for (String line : Files.readAllLines(rejects)) {
                if (line.contains("DUPLICATE_ORDER_ID") || line.contains("UNKNOWN_SKU")) {
                    records.add(new RejectRecord(line, null, Instant.EPOCH));
                }
            }
            require(records.size() == 2, "duplicate and unknown SKU rejected");
        } finally {
            Files.deleteIfExists(rejects);
        }
    }

    private static void duplicateLineRollbackCheck() {
        InventoryRepository inventory = InventoryRepository.fromSeed(Map.of(
                new InventoryKey(SKU, FulfillmentCenter.FC_EAST), new InventorySeed(5, 10.0)));
        Order order = new Order(new OrderId("ORD-000010"), OrderTier.STANDARD, false,
                List.of(new OrderLine(SKU, 3), new OrderLine(SKU, 3)), Instant.EPOCH, 1);
        require(!inventory.tryReserve(order).reserved(), "duplicate lines must fail atomically");
        require(inventory.companyCapacity(SKU) == 5, "failed reservation must not deduct stock");
    }

    private static void escalationAuditCheck() throws InterruptedException {
        InventoryRepository inventory = InventoryRepository.fromSeed(Map.of(
            new InventoryKey(SKU, FulfillmentCenter.FC_EAST), new InventorySeed(1, 10.0),
            new InventoryKey(SKU, FulfillmentCenter.FC_WEST), new InventorySeed(1, 10.0)));
        AuditTrail audit = new AuditTrail();
        OrderFulfillmentService service = new OrderFulfillmentService(inventory, audit, Clock.systemUTC(), 1_000.0);
        try {
            service.submit(order("ORD-000011", OrderTier.PRIORITY, 11));
            service.submit(order("ORD-000012", OrderTier.STANDARD, 12));
            Thread.sleep(250);
            long count = audit.all().stream().filter(event -> event.type() == AuditEventType.ORDER_ESCALATED).count();
            require(count == 1, "one standard order escalated");
            require(count == 1, "escalation audit emitted once");
        } finally {
            service.close();
        }
    }

    private static void partialAndRestockCheck() {
        Sku secondSku = new Sku("SKU-SECOND");
        Sku waitingSku = new Sku("SKU-WAITING");
        InventoryRepository inventory = InventoryRepository.fromSeed(Map.of(
                new InventoryKey(SKU, FulfillmentCenter.FC_EAST), new InventorySeed(1, 10.0),
            new InventoryKey(secondSku, FulfillmentCenter.FC_EAST), new InventorySeed(1, 20.0),
            new InventoryKey(waitingSku, FulfillmentCenter.FC_EAST), new InventorySeed(1, 10.0),
            new InventoryKey(waitingSku, FulfillmentCenter.FC_WEST), new InventorySeed(1, 10.0)));
        AuditTrail audit = new AuditTrail();
        OrderFulfillmentService service = new OrderFulfillmentService(inventory, audit, Clock.systemUTC(), 1_000.0);
        try {
            Order partial = new Order(new OrderId("ORD-000013"), OrderTier.STANDARD, true,
                    List.of(new OrderLine(SKU, 1), new OrderLine(secondSku, 2)), Instant.EPOCH, 13);
            require(service.submit(partial).allocations().size() == 1, "partial line shipped");
            require(service.deadLetterCount() == 1, "unfulfillable partial line dead-lettered");

            Order waiting = order("ORD-000014", OrderTier.STANDARD, 14, waitingSku);
            require(service.submit(waiting).status().name().equals("BACKORDERED"), "temporary shortage backordered");
            service.restock(waitingSku, FulfillmentCenter.FC_EAST, 1);
            require(service.status(new OrderId("ORD-000014")).name().equals("SHIPPED"), "restock retries order");
        } finally {
            service.close();
        }
    }

    private static Order order(String id, OrderTier tier, long sequence) {
        return order(id, tier, sequence, SKU);
    }

    private static Order order(String id, OrderTier tier, long sequence, Sku sku) {
        return new Order(new OrderId(id), tier, false, List.of(new OrderLine(sku, 2)),
                Instant.EPOCH, sequence);
    }

    private static String withChecksum(String line) {
        return line + "|" + String.format("%02d", Checksum.calculate(line));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
