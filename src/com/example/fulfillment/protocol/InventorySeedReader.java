package com.example.fulfillment.protocol;

import com.example.fulfillment.domain.FulfillmentCenter;
import com.example.fulfillment.domain.InventoryKey;
import com.example.fulfillment.domain.Sku;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InventorySeedReader {
    public Map<InventoryKey, InventorySeed> load(Path path) throws IOException {
        Map<InventoryKey, InventorySeed> result = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("Invalid inventory line " + lineNumber);
            }
            try {
                Sku sku = new Sku(fields[0]);
                FulfillmentCenter center = FulfillmentCenter.fromCode(fields[1]);
                int quantity = Integer.parseInt(fields[2]);
                double price = Double.parseDouble(fields[3]);
                if (quantity < 0 || price < 0 || Double.isNaN(price) || Double.isInfinite(price)) {
                    throw new IllegalArgumentException("Negative quantity or price");
                }
                InventoryKey key = new InventoryKey(sku, center);
                if (result.putIfAbsent(key, new InventorySeed(quantity, price)) != null) {
                    throw new IllegalArgumentException("Duplicate inventory key");
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid inventory line " + lineNumber, exception);
            }
        }
        return result;
    }

    public record InventorySeed(int quantity, double unitPrice) {
    }
}
