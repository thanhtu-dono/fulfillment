package com.example.fulfillment.backorder;

import com.example.fulfillment.domain.Order;

import java.time.Instant;

public record BackorderEntry(Order order, Instant enqueuedAt, long sequence) {
}
