package com.example.fulfillment.protocol;

import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.OrderId;
import com.example.fulfillment.domain.OrderLine;
import com.example.fulfillment.domain.OrderTier;
import com.example.fulfillment.domain.Sku;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OrderFeedParser {
    private static final Pattern LINE_ITEM = Pattern.compile("^(.+)x([0-9]+)$");
    private final Set<Sku> knownSkus;
    private final RejectWriter rejectWriter;
    private final Clock clock;
    private final Set<String> seenOrderIds = new HashSet<>();

    public OrderFeedParser(Set<Sku> knownSkus, RejectWriter rejectWriter, Clock clock) {
        this.knownSkus = Set.copyOf(knownSkus);
        this.rejectWriter = rejectWriter;
        this.clock = clock;
    }

    public void parse(List<String> lines, Consumer<ParsedOrder> consumer) throws Exception {
        List<List<String>> blocks = logicalBlocks(lines);
        for (int index = 0; index < blocks.size(); index++) {
            ParsedOrder parsed = parseBlock(blocks.get(index), index);
            if (parsed != null) {
                consumer.accept(parsed);
            }
        }
    }

    public void parseConcurrently(List<String> lines, int workerCount,
                                  Consumer<ParsedOrder> consumer) throws Exception {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        List<List<String>> blocks = logicalBlocks(lines);
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<ParsedOrder>> tasks = new ArrayList<>();
            for (int index = 0; index < blocks.size(); index++) {
                int sequence = index;
                tasks.add(() -> parseBlock(blocks.get(sequence), sequence));
            }
            for (var future : workers.invokeAll(tasks)) {
                ParsedOrder parsed = future.get();
                if (parsed != null) {
                    consumer.accept(parsed);
                }
            }
        } finally {
            workers.shutdown();
        }
    }

    private List<List<String>> logicalBlocks(List<String> lines) throws Exception {
        List<List<String>> blocks = new ArrayList<>();
        Set<String> structuralOrderIds = new HashSet<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.startsWith("O|")) {
                if (current != null) {
                    blocks.add(current);
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length > 1 && fields[1].matches("ORD-[0-9]{6}")
                        && !structuralOrderIds.add(fields[1])) {
                    reject(line, RejectReason.DUPLICATE_ORDER_ID);
                    current = null;
                    continue;
                }
                current = new ArrayList<>();
                current.add(line);
            } else if (line.startsWith("C|")) {
                if (current == null) {
                    reject(line, RejectReason.ORPHAN_CONTINUATION);
                    continue;
                }
                current.add(line);
            } else {
                reject(line, RejectReason.MALFORMED_FIELD);
            }
        }
        if (current != null) {
            blocks.add(current);
        }
        return blocks;
    }

    private ParsedOrder parseBlock(List<String> block, long sequence) throws Exception {
        OrderBuilder current = parseHeader(block.get(0));
        if (current == null) {
            return null;
        }
        for (int index = 1; index < block.size(); index++) {
            String line = block.get(index);
            if (!Checksum.matches(line)) {
                reject(line, RejectReason.CHECKSUM_MISMATCH);
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4 || !fields[1].equals(current.id.value())) {
                reject(line, RejectReason.ORPHAN_CONTINUATION);
                continue;
            }
            List<OrderLine> continuation = parseItems(line, fields[2]);
            if (continuation != null) {
                if (continuation.size() > 6) {
                    reject(line, RejectReason.MALFORMED_LINE_ITEM);
                } else {
                    current.lines.addAll(continuation);
                }
            }
        }
        return current.build(sequence);
    }

    private OrderBuilder parseHeader(String line) throws Exception {
        if (!Checksum.matches(line)) {
            reject(line, RejectReason.CHECKSUM_MISMATCH);
            return null;
        }
        String[] fields = line.split("\\|", -1);
        if (fields.length != 6 || !fields[1].matches("ORD-[0-9]{6}")
                || !(fields[2].equals("STD") || fields[2].equals("PRI"))
                || !fields[3].matches("[P-].{1}")) {
            reject(line, RejectReason.MALFORMED_FIELD);
            return null;
        }
        if (!seenOrderIds.add(fields[1])) {
            reject(line, RejectReason.DUPLICATE_ORDER_ID);
            return null;
        }
        List<OrderLine> items = parseItems(line, fields[4]);
        if (items == null || items.size() > 4) {
            if (items == null) {
                return null;
            }
            reject(line, RejectReason.MALFORMED_LINE_ITEM);
            return null;
        }
        return new OrderBuilder(new OrderId(fields[1]),
                fields[2].equals("PRI") ? OrderTier.PRIORITY : OrderTier.STANDARD,
                fields[3].charAt(0) == 'P', items);
    }

    private List<OrderLine> parseItems(String rawLine, String itemField) throws Exception {
        if (itemField.isBlank()) {
            reject(rawLine, RejectReason.MALFORMED_LINE_ITEM);
            return null;
        }
        List<OrderLine> result = new ArrayList<>();
        for (String token : itemField.split(";", -1)) {
            Matcher matcher = LINE_ITEM.matcher(token);
            if (!matcher.matches()) {
                reject(rawLine, RejectReason.MALFORMED_LINE_ITEM);
                return null;
            }
            try {
                Sku sku = new Sku(matcher.group(1));
                int quantity = Integer.parseInt(matcher.group(2));
                if (!knownSkus.contains(sku) || quantity <= 0) {
                    reject(rawLine, !knownSkus.contains(sku)
                            ? RejectReason.UNKNOWN_SKU : RejectReason.MALFORMED_LINE_ITEM);
                    return null;
                }
                result.add(new OrderLine(sku, quantity));
            } catch (RuntimeException exception) {
                reject(rawLine, RejectReason.MALFORMED_LINE_ITEM);
                return null;
            }
        }
        return result;
    }

    private void reject(String line, RejectReason reason) throws Exception {
        rejectWriter.write(new RejectRecord(line, reason, Instant.now(clock)));
    }

    private final class OrderBuilder {
        private final OrderId id;
        private final OrderTier tier;
        private final boolean partialAllowed;
        private final List<OrderLine> lines;

        private OrderBuilder(OrderId id, OrderTier tier, boolean partialAllowed, List<OrderLine> lines) {
            this.id = id;
            this.tier = tier;
            this.partialAllowed = partialAllowed;
            this.lines = new ArrayList<>(lines);
        }

        private ParsedOrder build(long sequence) {
            return new ParsedOrder(new Order(id, tier, partialAllowed, lines, Instant.now(clock), sequence));
        }
    }
}
