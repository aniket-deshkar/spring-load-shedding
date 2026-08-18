package io.github.aniketdeshkar.loadshedding;

import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerSheddingMetrics implements SheddingMetrics {
  private final MeterRegistry registry;

  public MicrometerSheddingMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void admitted(String route) {
    registry.counter("load.shedding.requests", "route", route, "outcome", "admitted").increment();
  }

  public void rejected(String route, RejectionReason reason) {
    registry
        .counter(
            "load.shedding.requests",
            "route",
            route,
            "outcome",
            "rejected",
            "reason",
            reason.name())
        .increment();
  }
}
