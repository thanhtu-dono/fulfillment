package com.example.fulfillment.protocol;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

public final class RejectWriter implements AutoCloseable {
    private final BufferedWriter writer;

    public RejectWriter(Path path) throws IOException {
        writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void write(RejectRecord record) throws IOException {
        writer.write(DateTimeFormatter.ISO_INSTANT.format(record.timestamp()));
        writer.write(" | ");
        writer.write(record.reason().name());
        writer.write(" | ");
        writer.write(record.rawLine());
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
