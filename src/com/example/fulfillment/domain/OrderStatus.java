package com.example.fulfillment.domain;

public enum OrderStatus {
    RECEIVED,
    RESERVED,
    BACKORDERED,
    ESCALATED,
    DEAD_LETTERED,
    SHIPPED,
    REJECTED
}
