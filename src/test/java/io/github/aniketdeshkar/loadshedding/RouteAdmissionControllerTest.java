package io.github.aniketdeshkar.loadshedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RouteAdmissionControllerTest {
  private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
  private static final Instant DEADLINE = Instant.EPOCH.plusSeconds(10);

  @Test
  void rejectsBeyondConcurrencyAndBoundedQueue() throws Exception {
    var controller =
        controller(new RoutePolicy(1, 1, Duration.ofSeconds(2), Duration.ofSeconds(3)));
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  controller.execute(
                      DEADLINE,
                      () -> {
                        started.countDown();
                        await(release);
                        return "first";
                      }));
      assertTrue(started.await(1, TimeUnit.SECONDS));
      var second = executor.submit(() -> controller.execute(DEADLINE, () -> "second"));
      try {
        awaitQueued(controller);
        var error =
            assertThrows(
                LoadShedException.class, () -> controller.execute(DEADLINE, () -> "third"));
        assertEquals(RejectionReason.SATURATED, error.reason());
        assertEquals(Duration.ofSeconds(3), error.retryAfter());
      } finally {
        release.countDown();
      }
      assertEquals("first", first.get());
      assertEquals("second", second.get());
    }
  }

  @Test
  void queueWaitTimesOutDeterministically() throws Exception {
    var controller = controller(new RoutePolicy(1, 1, Duration.ofMillis(20), Duration.ZERO));
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var holder =
          executor.submit(
              () ->
                  controller.execute(
                      DEADLINE,
                      () -> {
                        started.countDown();
                        await(release);
                        return true;
                      }));
      started.await();
      try {
        var error =
            assertThrows(LoadShedException.class, () -> controller.execute(DEADLINE, () -> false));
        assertEquals(RejectionReason.QUEUE_TIMEOUT, error.reason());
      } finally {
        release.countDown();
      }
      holder.get();
    }
  }

  @Test
  void rejectsExpiredDeadlineBeforeWork() {
    var calls = new AtomicInteger();
    var controller = controller(new RoutePolicy(1, 0, Duration.ZERO, Duration.ZERO));
    var error =
        assertThrows(
            LoadShedException.class,
            () -> controller.execute(Instant.EPOCH, () -> calls.incrementAndGet()));
    assertEquals(RejectionReason.DEADLINE_EXCEEDED, error.reason());
    assertEquals(0, calls.get());
  }

  @Test
  void dependencyHealthRejectsBeforeAdmission() {
    var metrics = new RecordingMetrics();
    var controller =
        new RouteAdmissionController(
            "search",
            new RoutePolicy(1, 0, Duration.ZERO, Duration.ofSeconds(1)),
            CLOCK,
            route -> false,
            metrics);
    var error =
        assertThrows(LoadShedException.class, () -> controller.execute(DEADLINE, () -> "never"));
    assertEquals(RejectionReason.DEPENDENCY_UNHEALTHY, error.reason());
    assertEquals(List.of("search:DEPENDENCY_UNHEALTHY"), metrics.rejected);
  }

  @Test
  void releasesPermitWhenWorkFails() {
    var controller = controller(new RoutePolicy(1, 0, Duration.ZERO, Duration.ZERO));
    assertThrows(
        IllegalStateException.class,
        () ->
            controller.execute(
                DEADLINE,
                () -> {
                  throw new IllegalStateException("failed");
                }));
    assertEquals("ok", controller.execute(DEADLINE, () -> "ok"));
  }

  @Test
  void registryAppliesDifferentPoliciesAndRejectsUnknownRoute() {
    var registry =
        new LoadSheddingRegistry(
            Map.of(
                "read",
                new RoutePolicy(2, 0, Duration.ZERO, Duration.ZERO),
                "write",
                new RoutePolicy(1, 0, Duration.ZERO, Duration.ZERO)),
            CLOCK,
            DependencyHealth.alwaysHealthy(),
            SheddingMetrics.noOp());
    assertEquals("ok", registry.execute("read", DEADLINE, () -> "ok"));
    assertEquals(2, new RoutePolicy(2, 0, Duration.ZERO, Duration.ZERO).concurrencyLimit());
    assertEquals(
        RejectionReason.UNKNOWN_ROUTE,
        assertThrows(
                LoadShedException.class, () -> registry.execute("missing", DEADLINE, () -> "no"))
            .reason());
  }

  @Test
  void controlledVirtualThreadLoadNeverExceedsLimit() throws Exception {
    var controller = controller(new RoutePolicy(3, 20, Duration.ofSeconds(2), Duration.ZERO));
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(3);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 20; i++)
        tasks.add(
            executor.submit(
                () ->
                    controller.execute(
                        DEADLINE,
                        () -> {
                          int current = active.incrementAndGet();
                          maximum.accumulateAndGet(current, Math::max);
                          entered.countDown();
                          await(release);
                          active.decrementAndGet();
                          return current;
                        })));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      for (var task : tasks) task.get();
    }
    assertEquals(3, maximum.get());
    assertEquals(0, controller.active());
    assertEquals(0, controller.queued());
  }

  @Test
  void validatesRoutePolicy() {
    assertThrows(
        IllegalArgumentException.class, () -> new RoutePolicy(0, 0, Duration.ZERO, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new RoutePolicy(1, -1, Duration.ZERO, Duration.ZERO));
  }

  private static RouteAdmissionController controller(RoutePolicy policy) {
    return new RouteAdmissionController(
        "route", policy, CLOCK, DependencyHealth.alwaysHealthy(), SheddingMetrics.noOp());
  }

  private static void awaitQueued(RouteAdmissionController controller) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (controller.queued() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
    assertEquals(1, controller.queued());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private static final class RecordingMetrics implements SheddingMetrics {
    private final List<String> rejected = new ArrayList<>();

    public void admitted(String route) {}

    public void rejected(String route, RejectionReason reason) {
      rejected.add(route + ":" + reason);
    }
  }
}
