package com.example.fulfillment.protocol;

import java.time.Instant;

@FunctionalInterface
public interface WarningWriter {
    void write(Instant timestamp, String message) throws Exception;
}
