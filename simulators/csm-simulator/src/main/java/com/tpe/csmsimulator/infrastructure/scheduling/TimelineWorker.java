package com.tpe.csmsimulator.infrastructure.scheduling;

import com.tpe.csmsimulator.application.CsmSimulation;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Runs the simulation's due work every {@code tpe.sim.tick} on one thread (specs/17-csm-simulator.md §3.2). */
public final class TimelineWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TimelineWorker.class);

    private final CsmSimulation simulation;
    private final Duration tick;
    private ScheduledExecutorService executor;

    public TimelineWorker(CsmSimulation simulation, Duration tick) {
        this.simulation = simulation;
        this.tick = tick;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("sim-timeline").factory());
        executor.scheduleWithFixedDelay(this::runDue, 0, tick.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized void stop() {
        executor.shutdownNow();
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    private void runDue() {
        try {
            simulation.tick();
        } catch (RuntimeException e) {
            // a failed tick must not stop the timeline
            log.error("timeline tick failed", e);
        }
    }
}
