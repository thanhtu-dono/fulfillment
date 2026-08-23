package com.example.fulfillment.backorder;

import com.example.fulfillment.domain.Order;
import com.example.fulfillment.domain.OrderTier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class BackorderService implements AutoCloseable {
    private final PriorityBlockingQueue<BackorderEntry> queue = new PriorityBlockingQueue<>(32, comparator());
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Semaphore restockSignals = new Semaphore(0);
    private final Clock clock;
    private final double timeScale;
    private final Consumer<BackorderEntry> processor;
    private final Consumer<Order> escalationListener;
    private Thread worker;

    public BackorderService(Clock clock, double timeScale, Consumer<BackorderEntry> processor,
                            Consumer<Order> escalationListener) {
        if (timeScale <= 0) {
            throw new IllegalArgumentException("Time scale must be positive");
        }
        this.clock = clock;
        this.timeScale = timeScale;
        this.processor = processor;
        this.escalationListener = escalationListener;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::run, "backorder-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void enqueue(Order order) {
        enqueue(order, Instant.now(clock));
    }

    public void enqueue(Order order, Instant enqueuedAt) {
        queue.offer(new BackorderEntry(order, enqueuedAt, sequence.getAndIncrement()));
    }

    public void signalRestock() {
        if (running.get()) {
            restockSignals.release();
        }
    }

    public int size() {
        return queue.size();
    }

    public void processNow() {
        int count = queue.size();
        for (int index = 0; index < count; index++) {
            BackorderEntry entry = queue.poll();
            if (entry == null) {
                return;
            }
            processor.accept(entry);
        }
    }

    private void run() {
        while (running.get()) {
            escalateEligible(escalationListener);
            try {
                if (restockSignals.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    processNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Comparator<BackorderEntry> comparator() {
        return Comparator.comparing((BackorderEntry entry) -> entry.order().tier() == OrderTier.PRIORITY ? 0 : 1)
                .thenComparing(entry -> entry.order().submittedAt())
                .thenComparingLong(BackorderEntry::sequence);
    }

    public int escalateEligible(Consumer<Order> escalationListener) {
        Instant now = Instant.now(clock);
        boolean hasPriority = queue.stream().anyMatch(entry -> entry.order().tier() == OrderTier.PRIORITY);
        if (!hasPriority) {
            return 0;
        }
        int escalated = 0;
        for (BackorderEntry entry : queue.toArray(BackorderEntry[]::new)) {
            Duration waited = Duration.between(entry.enqueuedAt(), now);
            if (entry.order().tier() == OrderTier.STANDARD
                    && waited.toMillis() > (long) (90_000 / timeScale)
                    && queue.remove(entry)) {
                Order order = entry.order();
                queue.offer(new BackorderEntry(new Order(order.id(), OrderTier.PRIORITY,
                        order.partialAllowed(), order.lines(), order.submittedAt(), order.ingestionSequence()),
                        entry.enqueuedAt(), entry.sequence()));
                escalationListener.accept(order);
                escalated++;
            }
        }
        return escalated;
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
