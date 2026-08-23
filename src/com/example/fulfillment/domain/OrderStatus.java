package com.example.fulfillment.domain;

public enum OrderStatus {
    RECEIVED,
    RESERVED,
    BACKORDERED,
    PARTIALLY_SHIPPED,
    ESCALATED,
    DEAD_LETTERED,
    SHIPPED,
    REJECTED
}
