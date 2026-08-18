package io.github.aniketdeshkar.loadshedding;

import java.time.Duration;

public record RoutePolicy(
    int concurrencyLimit, int queueCapacity, Duration maxQueueWait, Duration retryAfter) {
  public RoutePolicy {
    if (concurrencyLimit <= 0
        || queueCapacity < 0
        || maxQueueWait.isNegative()
        || retryAfter.isNegative()) throw new IllegalArgumentException("invalid route policy");
  }
}
