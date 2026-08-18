package io.github.aniketdeshkar.loadshedding;

public interface SheddingMetrics {
  void admitted(String route);

  void rejected(String route, RejectionReason reason);

  static SheddingMetrics noOp() {
    return new SheddingMetrics() {
      public void admitted(String route) {}

      public void rejected(String route, RejectionReason reason) {}
    };
  }
}
