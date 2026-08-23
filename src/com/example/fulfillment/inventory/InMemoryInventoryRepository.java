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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Map<OrderLine, InventoryKey> selected = selectCenters(order.lines());
        if (selected == null) {
            return ReservationAttempt.failed();
        }

        List<InventoryKey> keys = selected.values().stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (InventoryKey key : keys) {
                ReentrantLock lock = locks.get(key);
                if (lock == null) {
                    return ReservationAttempt.failed();
                }
                lock.lock();
                acquired.add(lock);
            }
            for (Map.Entry<OrderLine, InventoryKey> entry : selected.entrySet()) {
                if (stockByKey.get(entry.getValue()).quantity < entry.getKey().quantity()) {
                    return ReservationAttempt.failed();
                }
            }
            List<ReservationAllocation> allocations = new ArrayList<>();
            for (Map.Entry<OrderLine, InventoryKey> entry : selected.entrySet()) {
                Stock stock = stockByKey.get(entry.getValue());
                stock.quantity -= entry.getKey().quantity();
                allocations.add(new ReservationAllocation(entry.getKey(), entry.getValue().center(), stock.unitPrice));
            }
            return new ReservationAttempt(true, allocations);
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
    }

    private Map<OrderLine, InventoryKey> selectCenters(List<OrderLine> lines) {
        Map<OrderLine, InventoryKey> selected = new LinkedHashMap<>();
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
            selected.put(line, candidate);
        }
        return selected;
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
        return stockByKey.entrySet().stream()
                .filter(entry -> entry.getKey().sku().equals(sku))
                .mapToInt(entry -> {
                    ReentrantLock lock = locks.get(entry.getKey());
                    lock.lock();
                    try {
                        return entry.getValue().quantity;
                    } finally {
                        lock.unlock();
                    }
                }).sum();
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
