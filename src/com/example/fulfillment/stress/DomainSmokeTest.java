package com.example.fulfillment.stress;

import com.example.fulfillment.domain.OrderId;
import com.example.fulfillment.domain.OrderLine;
import com.example.fulfillment.domain.Sku;

public final class DomainSmokeTest {
    private DomainSmokeTest() {
    }

    public static void main(String[] args) {
        require(new OrderId("ORD-123456").value().equals("ORD-123456"), "order id");
        require(new OrderLine(new Sku("SKU-1001"), 2).quantity() == 2, "positive quantity");
        expectFailure(() -> new OrderId("ORD-12345"));
        expectFailure(() -> new OrderLine(new Sku("SKU-1001"), 0));
        System.out.println("DOMAIN_SMOKE_TEST_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected failure");
        } catch (IllegalArgumentException expected) {
        }
    }
}
