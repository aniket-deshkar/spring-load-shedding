package io.github.aniketdeshkar.loadshedding;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

public final class LoadSheddingRegistry {
  private final Map<String, RouteAdmissionController> routes;
  private final SheddingMetrics metrics;

  public LoadSheddingRegistry(
      Map<String, RoutePolicy> policies,
      Clock clock,
      DependencyHealth health,
      SheddingMetrics metrics) {
    this.metrics = metrics;
    this.routes =
        policies.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry ->
                        new RouteAdmissionController(
                            entry.getKey(), entry.getValue(), clock, health, metrics)));
  }

  public <T> T execute(String route, Instant deadline, Supplier<T> work) {
    var controller = routes.get(route);
    if (controller == null) {
      metrics.rejected(route, RejectionReason.UNKNOWN_ROUTE);
      throw new LoadShedException(RejectionReason.UNKNOWN_ROUTE, java.time.Duration.ZERO);
    }
    return controller.execute(deadline, work);
  }

  public RouteAdmissionController route(String route) {
    return routes.get(route);
  }
}
