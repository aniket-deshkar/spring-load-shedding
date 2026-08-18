package io.github.aniketdeshkar.loadshedding;

import java.time.Duration;

public final class LoadShedException extends RuntimeException {
  private final RejectionReason reason;
  private final Duration retryAfter;

  public LoadShedException(RejectionReason reason, Duration retryAfter) {
    super(reason.name());
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public RejectionReason reason() {
    return reason;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
