package com.example.fulfillment.protocol;

import java.time.Instant;

public record RejectRecord(String rawLine, RejectReason reason, Instant timestamp) {
}
