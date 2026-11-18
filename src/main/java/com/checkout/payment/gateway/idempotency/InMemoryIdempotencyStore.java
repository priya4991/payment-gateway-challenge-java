package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Process-local implementation; replace with a durable store for multi-instance deployments. */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  @Override
  public Claim lookup(String key, String requestFingerprint) {
    Entry current = entries.get(key);
    return current == null ? new Claim(Status.NEW, null) : resolveClaim(current, requestFingerprint);
  }

  @Override
  public Claim claim(String key, String requestFingerprint) {
    Entry newEntry = new Entry(requestFingerprint, Status.IN_PROGRESS, null);
    Entry existing = entries.putIfAbsent(key, newEntry);
    return existing == null
        ? new Claim(Status.NEW, null)
        : resolveClaim(existing, requestFingerprint);
  }

  private static Claim resolveClaim(Entry entry, String requestFingerprint) {
    if (!entry.requestFingerprint().equals(requestFingerprint)) {
      return new Claim(Status.REQUEST_MISMATCH, null);
    }
    return new Claim(entry.status(), copy(entry.response()));
  }

  @Override
  public void complete(String key, PostPaymentResponse response) {
    entries.compute(key, (ignored, current) -> {
      if (current == null || current.status() != Status.IN_PROGRESS) {
        throw new IllegalStateException("Idempotency key is not in progress");
      }
      return new Entry(current.requestFingerprint(), Status.COMPLETED, copy(response));
    });
  }

  @Override
  public void fail(String key) {
    entries.computeIfPresent(key, (ignored, current) ->
        new Entry(current.requestFingerprint(), Status.FAILED, null));
  }

  private static PostPaymentResponse copy(PostPaymentResponse response) {
    if (response == null) {
      return null;
    }
    PostPaymentResponse copy = new PostPaymentResponse();
    copy.setId(response.getId());
    copy.setStatus(response.getStatus());
    copy.setCardNumberLastFour(response.getCardNumberLastFour());
    copy.setExpiryMonth(response.getExpiryMonth());
    copy.setExpiryYear(response.getExpiryYear());
    copy.setCurrency(response.getCurrency());
    copy.setAmount(response.getAmount());
    return copy;
  }

  private record Entry(String requestFingerprint, Status status, PostPaymentResponse response) {
  }
}