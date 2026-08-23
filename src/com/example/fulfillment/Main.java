package com.example.fulfillment;

import com.example.fulfillment.application.OrderFulfillmentService;
import com.example.fulfillment.audit.AuditTrail;
import com.example.fulfillment.domain.FulfillmentCenter;
import com.example.fulfillment.domain.OrderId;
import com.example.fulfillment.domain.Sku;
import com.example.fulfillment.inventory.InventoryRepository;
import com.example.fulfillment.protocol.InventorySeedReader;
import com.example.fulfillment.protocol.OrderFeedParser;
import com.example.fulfillment.protocol.ParsedOrder;
import com.example.fulfillment.protocol.RejectWriter;
import com.example.fulfillment.stress.StressTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path workingDirectory = Path.of(".");
        InventorySeedReader seedReader = new InventorySeedReader();
        var seed = seedReader.load(workingDirectory.resolve("inventory_seed.txt"));
        InventoryRepository inventory = InventoryRepository.fromSeed(seed);
        AuditTrail auditTrail = new AuditTrail();

        try (RejectWriter rejectWriter = new RejectWriter(workingDirectory.resolve("rejects.log"));
             OrderFulfillmentService service = new OrderFulfillmentService(inventory, auditTrail,
                     Clock.systemUTC(), 1.0)) {
            Set<Sku> knownSkus = seed.keySet().stream().map(key -> key.sku()).collect(java.util.stream.Collectors.toSet());
                OrderFeedParser parser = new OrderFeedParser(knownSkus, rejectWriter, Clock.systemUTC(),
                    System.out::println);
            List<ParsedOrder> parsedOrders = new java.util.ArrayList<>();
                parser.parseConcurrently(Files.readAllLines(workingDirectory.resolve("order_feed.txt")), 4,
                    parsedOrders::add);

            ExecutorService ingestionWorkers = Executors.newFixedThreadPool(4);
            for (ParsedOrder parsedOrder : parsedOrders) {
                ingestionWorkers.submit(() -> service.submit(parsedOrder.order()));
            }
            runConsole(service);
            ingestionWorkers.shutdown();
            if (!ingestionWorkers.awaitTermination(10, TimeUnit.SECONDS)) {
                ingestionWorkers.shutdownNow();
                ingestionWorkers.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private static void runConsole(OrderFulfillmentService service) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) {
                    continue;
                }
                String[] tokens = input.split("\\s+");
                try {
                    switch (tokens[0].toUpperCase()) {
                        case "STATUS" -> printStatus(service);
                        case "REPORT" -> printReport(service);
                        case "RESTOCK" -> restock(service, tokens);
                        case "AUDIT" -> audit(service, tokens);
                        case "STRESS-TEST" -> runStressTest();
                        case "EXIT" -> { printReport(service); return; }
                        default -> System.out.println("Unknown command");
                    }
                } catch (RuntimeException exception) {
                    System.out.println("Command rejected: " + exception.getMessage());
                }
            }
        }
    }

    private static void printStatus(OrderFulfillmentService service) {
        service.inventory().snapshot().forEach((key, stock) ->
                System.out.printf("%s | %s | qty=%d | price=%.2f%n",
                        key.sku(), key.center(), stock.quantity(), stock.unitPrice()));
        System.out.println("backorders=" + service.backorderCount()
                + " deadLetters=" + service.deadLetterCount());
    }

    private static void printReport(OrderFulfillmentService service) {
        long submitted = service.submittedCount();
        double successRate = submitted == 0 ? 0 : (service.shippedCount() * 100.0 / submitted);
        System.out.printf("submitted=%d shipped=%d successRate=%.2f%% revenue=%.2f deadLetters=%d%n",
                submitted, service.shippedCount(), successRate, service.revenue(), service.deadLetterCount());
    }

    private static void restock(OrderFulfillmentService service, String[] tokens) {
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Usage: RESTOCK <SKU> <CENTER> <QTY>");
        }
        service.restock(new Sku(tokens[1]), FulfillmentCenter.fromCode(tokens[2]), Integer.parseInt(tokens[3]));
        System.out.println("Restock applied");
    }

    private static void audit(OrderFulfillmentService service, String[] tokens) {
        if (tokens.length != 2) {
            throw new IllegalArgumentException("Usage: AUDIT <ORDER_ID>");
        }
        service.auditTrail().forOrder(new OrderId(tokens[1])).forEach(System.out::println);
    }

    private static void runStressTest() {
        try {
            StressTest.main(new String[0]);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("Stress test interrupted");
        }
    }
}
