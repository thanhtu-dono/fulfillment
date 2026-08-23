package com.example.fulfillment.inventory;

import com.example.fulfillment.domain.FulfillmentCenter;
import com.example.fulfillment.domain.InventoryKey;
import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.OrderLine;
import com.example.fulfillment.domain.ReservationAllocation;
import com.example.fulfillment.domain.Sku;
import com.example.fulfillment.protocol.InventorySeedReader.InventorySeed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class InMemoryInventoryRepository implements InventoryRepository {
    private final ConcurrentHashMap<InventoryKey, Stock> stockByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InventoryKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public InMemoryInventoryRepository(Map<InventoryKey, InventorySeed> seed) {
        seed.forEach((key, value) -> {
            stockByKey.put(key, new Stock(value.quantity(), value.unitPrice()));
            locks.put(key, new ReentrantLock());
        });
    }

    @Override
    public ReservationAttempt tryReserve(Order order) {
        return tryReserve(order, attempt -> { });
    }

    @Override
    public ReservationAttempt tryReserve(Order order, Consumer<ReservationAttempt> completion) {
        List<AllocationPlan> selected = selectCenters(order.lines());
        if (selected == null) {
            return ReservationAttempt.failed();
        }

        List<InventoryKey> keys = selected.stream().map(AllocationPlan::key)
            .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (InventoryKey key : keys) {
                ReentrantLock lock = locks.get(key);
                if (lock == null) {
                    ReservationAttempt failed = ReservationAttempt.failed();
                    completion.accept(failed);
                    return failed;
                }
                lock.lock();
                acquired.add(lock);
            }
            Map<InventoryKey, Integer> demandByKey = new HashMap<>();
            for (AllocationPlan plan : selected) {
                demandByKey.merge(plan.key(), plan.line().quantity(), Math::addExact);
            }
            for (Map.Entry<InventoryKey, Integer> demand : demandByKey.entrySet()) {
                if (stockByKey.get(demand.getKey()).quantity < demand.getValue()) {
                    ReservationAttempt failed = ReservationAttempt.failed();
                    completion.accept(failed);
                    return failed;
                }
            }
            List<ReservationAllocation> allocations = new ArrayList<>();
            for (AllocationPlan plan : selected) {
                Stock stock = stockByKey.get(plan.key());
                stock.quantity -= plan.line().quantity();
                allocations.add(new ReservationAllocation(plan.line(), plan.key().center(), stock.unitPrice));
            }
            ReservationAttempt successful = new ReservationAttempt(true, allocations);
            completion.accept(successful);
            return successful;
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
    }

    private List<AllocationPlan> selectCenters(List<OrderLine> lines) {
        List<AllocationPlan> selected = new ArrayList<>();
        for (OrderLine line : lines) {
            InventoryKey candidate = stockByKey.keySet().stream()
                    .filter(key -> key.sku().equals(line.sku()))
                    .sorted()
                    .filter(key -> stockByKey.get(key).quantity >= line.quantity())
                    .findFirst()
                    .orElse(null);
            if (candidate == null) {
                return null;
            }
            selected.add(new AllocationPlan(line, candidate));
        }
        return selected;
    }

    private record AllocationPlan(OrderLine line, InventoryKey key) {
    }

    @Override
    public void restock(Sku sku, FulfillmentCenter center, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be positive");
        }
        InventoryKey key = new InventoryKey(sku, center);
        Stock stock = stockByKey.get(key);
        ReentrantLock lock = locks.get(key);
        if (stock == null || lock == null) {
            throw new IllegalArgumentException("Unknown inventory key: " + key);
        }
        lock.lock();
        try {
            stock.quantity = Math.addExact(stock.quantity, quantity);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int companyCapacity(Sku sku) {
        List<InventoryKey> keys = stockByKey.keySet().stream()
                .filter(key -> key.sku().equals(sku))
                .sorted()
                .toList();
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (InventoryKey key : keys) {
                ReentrantLock lock = locks.get(key);
                lock.lock();
                acquired.add(lock);
            }
            return keys.stream().mapToInt(key -> stockByKey.get(key).quantity).sum();
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
    }

    @Override
    public Map<InventoryKey, StockSnapshot> snapshot() {
        Map<InventoryKey, StockSnapshot> result = new LinkedHashMap<>();
        stockByKey.keySet().stream().sorted().forEach(key -> {
            ReentrantLock lock = locks.get(key);
            lock.lock();
            try {
                Stock stock = stockByKey.get(key);
                result.put(key, new StockSnapshot(stock.quantity, stock.unitPrice));
            } finally {
                lock.unlock();
            }
        });
        return Map.copyOf(result);
    }

    private static final class Stock {
        private int quantity;
        private final double unitPrice;

        private Stock(int quantity, double unitPrice) {
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }
}
