package io.github.aniketdeshkar.loadshedding;

@FunctionalInterface
public interface DependencyHealth {
  boolean healthy(String route);

  static DependencyHealth alwaysHealthy() {
    return route -> true;
  }
}
