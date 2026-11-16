package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentResponse;

/** Stores the outcome associated with a payment idempotency key. */
public interface IdempotencyStore {

  /** Looks up an existing key without reserving it when absent. */
  Claim lookup(String key, String requestFingerprint);

  Claim claim(String key, String requestFingerprint);

  void complete(String key, PostPaymentResponse response);

  void fail(String key);

  enum Status {
    NEW,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    REQUEST_MISMATCH
  }

  record Claim(Status status, PostPaymentResponse response) {
  }
}