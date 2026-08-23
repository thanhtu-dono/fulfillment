package com.example.fulfillment.protocol;

public enum RejectReason {
    CHECKSUM_MISMATCH,
    MALFORMED_FIELD,
    MALFORMED_LINE_ITEM,
    UNKNOWN_SKU,
    ORPHAN_CONTINUATION,
    DUPLICATE_ORDER_ID
}
