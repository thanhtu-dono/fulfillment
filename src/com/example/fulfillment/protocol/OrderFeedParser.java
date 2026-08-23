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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OrderFeedParser {
    private static final Pattern LINE_ITEM = Pattern.compile("^(.+)x([0-9]+)$");
    private final Set<Sku> knownSkus;
    private final RejectWriter rejectWriter;
    private final Clock clock;
    private final Set<String> seenOrderIds = new HashSet<>();
    private long sequence;

    public OrderFeedParser(Set<Sku> knownSkus, RejectWriter rejectWriter, Clock clock) {
        this.knownSkus = Set.copyOf(knownSkus);
        this.rejectWriter = rejectWriter;
        this.clock = clock;
    }

    public void parse(List<String> lines, Consumer<ParsedOrder> consumer) throws Exception {
        OrderBuilder current = null;
        for (String line : lines) {
            if (line.startsWith("O|")) {
                if (current != null) {
                    consumer.accept(current.build());
                }
                current = parseHeader(line);
            } else if (line.startsWith("C|")) {
                if (current == null) {
                    reject(line, RejectReason.ORPHAN_CONTINUATION);
                    continue;
                }
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
                    current.lines.addAll(continuation);
                }
            } else {
                reject(line, RejectReason.MALFORMED_FIELD);
            }
        }
        if (current != null) {
            consumer.accept(current.build());
        }
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

        private ParsedOrder build() {
            return new ParsedOrder(new Order(id, tier, partialAllowed, lines, Instant.now(clock), sequence++));
        }
    }
}
