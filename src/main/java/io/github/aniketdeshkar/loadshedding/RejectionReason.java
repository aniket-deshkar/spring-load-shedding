package io.github.aniketdeshkar.loadshedding;

public enum RejectionReason {
  SATURATED,
  QUEUE_TIMEOUT,
  DEADLINE_EXCEEDED,
  DEPENDENCY_UNHEALTHY,
  INTERRUPTED,
  UNKNOWN_ROUTE
}
