package io.github.aniketdeshkar.loadshedding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class RouteAdmissionController {
  private final String route;
  private final RoutePolicy policy;
  private final Semaphore permits;
  private final AtomicInteger queued = new AtomicInteger();
  private final Clock clock;
  private final DependencyHealth health;
  private final SheddingMetrics metrics;

  public RouteAdmissionController(
      String route,
      RoutePolicy policy,
      Clock clock,
      DependencyHealth health,
      SheddingMetrics metrics) {
    this.route = route;
    this.policy = policy;
    this.clock = clock;
    this.health = health;
    this.metrics = metrics;
    permits = new Semaphore(policy.concurrencyLimit(), true);
  }

  public <T> T execute(Instant deadline, Supplier<T> work) {
    if (!clock.instant().isBefore(deadline)) throw reject(RejectionReason.DEADLINE_EXCEEDED);
    if (!health.healthy(route)) throw reject(RejectionReason.DEPENDENCY_UNHEALTHY);
    boolean acquired = permits.tryAcquire();
    if (!acquired) {
      int position = queued.incrementAndGet();
      if (position > policy.queueCapacity()) {
        queued.decrementAndGet();
        throw reject(RejectionReason.SATURATED);
      }
      try {
        Duration remaining = Duration.between(clock.instant(), deadline);
        Duration wait =
            remaining.compareTo(policy.maxQueueWait()) < 0 ? remaining : policy.maxQueueWait();
        if (!wait.isPositive()) throw reject(RejectionReason.DEADLINE_EXCEEDED);
        acquired = permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
        if (!acquired)
          throw reject(
              clock.instant().isBefore(deadline)
                  ? RejectionReason.QUEUE_TIMEOUT
                  : RejectionReason.DEADLINE_EXCEEDED);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw reject(RejectionReason.INTERRUPTED);
      } finally {
        queued.decrementAndGet();
      }
    }
    metrics.admitted(route);
    try {
      return work.get();
    } finally {
      permits.release();
    }
  }

  public int active() {
    return policy.concurrencyLimit() - permits.availablePermits();
  }

  public int queued() {
    return queued.get();
  }

  private LoadShedException reject(RejectionReason reason) {
    metrics.rejected(route, reason);
    return new LoadShedException(reason, policy.retryAfter());
  }
}
