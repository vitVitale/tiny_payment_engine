package com.tpe.csmgateway.infrastructure.transmit;

import com.tpe.csmgateway.application.transmit.Transmitter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The transmit worker (specs/15-csm-gateway.md §6.5): every {@code tpe.csm.transmit.poll-interval} each
 * {@link Transmitter} attempts its due rows and gives up the expired ones. It runs on every instance; the claim's
 * lease keeps two instances from attempting one row at once. Started and stopped with the application context.
 */
public class TransmitWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransmitWorker.class);

    private final List<Transmitter> transmitters;
    private final Duration interval;

    private ScheduledExecutorService scheduler;

    public TransmitWorker(List<Transmitter> transmitters, Duration interval) {
        this.transmitters = List.copyOf(transmitters);
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("csm-transmit-worker").daemon().factory());
        // with a fixed delay; a poll that throws is logged, not cancelled
        scheduler.scheduleWithFixedDelay(this::poll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll() {
        for (Transmitter transmitter : transmitters) {
            try {
                transmitter.transmitDue();
            } catch (RuntimeException e) {
                log.warn("transmit poll failed, trying again in {}: {}", interval, e.toString());
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
