package com.example.mofid.balance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on a thread pool behind a start gate: every worker blocks on the same latch and they are
 * released together, which maximises the overlap between transactions and so the chance of exposing
 * a race. A hard timeout turns a deadlock or lost wake-up into a test failure instead of a hung build.
 */
public final class ConcurrentRunner {

    /** The result of one task: {@code error == null} means it completed normally. */
    public record Outcome(int taskIndex, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    private ConcurrentRunner() {
    }

    public static List<Outcome> run(int threads, List<Runnable> tasks, Duration timeout) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                int index = i;
                Runnable task = tasks.get(i);
                futures.add(executor.submit(() -> {
                    startGate.await();
                    try {
                        task.run();
                        return new Outcome(index, null);
                    } catch (Throwable t) {
                        return new Outcome(index, t);
                    }
                }));
            }
            startGate.countDown();

            executor.shutdown();
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Tasks did not finish within " + timeout + " (deadlock or starvation?)");
            }
            List<Outcome> outcomes = new ArrayList<>(futures.size());
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
