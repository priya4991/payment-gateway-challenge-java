package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.PaymentProcessingException;
import com.checkout.payment.gateway.exception.PaymentRejectedException;
import com.checkout.payment.gateway.idempotency.IdempotencyStore;
import com.checkout.payment.gateway.idempotency.PaymentRequestFingerprinter;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.PaymentRequestValidator;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
public class PaymentGatewayService {

  private final PaymentsRepository paymentsRepository;
  private final PaymentRequestValidator validator;
  private final BankClient bankClient;
  private final IdempotencyStore idempotencyStore;
  private final PaymentRequestFingerprinter fingerprinter;

  public PaymentGatewayService(PaymentsRepository paymentsRepository,
      PaymentRequestValidator validator, BankClient bankClient, IdempotencyStore idempotencyStore,
      PaymentRequestFingerprinter fingerprinter) {
    this.paymentsRepository = paymentsRepository;
    this.validator = validator;
    this.bankClient = bankClient;
    this.idempotencyStore = idempotencyStore;
    this.fingerprinter = fingerprinter;
  }

  public PostPaymentResponse getPaymentById(UUID id) {
    log.debug("Requesting access to payment with ID {}", id);
    return paymentsRepository.get(id).orElseThrow(() -> new EventProcessingException("Invalid ID"));
  }

  public PostPaymentResponse processPayment(PostPaymentRequest request, String idempotencyKey) {
    String key = normalizeIdempotencyKey(idempotencyKey);
    String requestFingerprint = fingerprinter.fingerprint(request);
    IdempotencyStore.Claim existing = idempotencyStore.lookup(key, requestFingerprint);
    if (existing.status() != IdempotencyStore.Status.NEW) {
      return resolveClaim(request, key, existing);
    }

    // Do not reserve a key for requests that fail validation.
    validator.validate(request);
    return resolveClaim(request, key, idempotencyStore.claim(key, requestFingerprint));
  }

  private PostPaymentResponse resolveClaim(PostPaymentRequest request, String key,
      IdempotencyStore.Claim claim) {
    switch (claim.status()) {
      case COMPLETED:
        return claim.response();
      case IN_PROGRESS:
        throw new IdempotencyConflictException(
            "A payment using this Idempotency-Key is already in progress");
      case REQUEST_MISMATCH:
        throw new IdempotencyConflictException(
            "This Idempotency-Key was already used with a different request");
      case FAILED:
        throw new PaymentProcessingException("The original payment attempt failed", null);
      case NEW:
        return processNewPayment(request, key);
      default:
        throw new IllegalStateException("Unknown idempotency claim status");
    }
  }

  private PostPaymentResponse processNewPayment(PostPaymentRequest request, String idempotencyKey) {
    try {
      PostPaymentResponse payment = createPayment(request);
      idempotencyStore.complete(idempotencyKey, payment);
      return payment;
    } catch (RuntimeException ex) {
      // Retain the key after failures: a retry must not risk submitting an ambiguous payment again.
      idempotencyStore.fail(idempotencyKey);
      throw ex;
    }
  }

  private PostPaymentResponse createPayment(PostPaymentRequest request) {
    BankPaymentResponse bankResponse = submitToBank(request);

    PostPaymentResponse payment = new PostPaymentResponse();
    payment.setId(UUID.randomUUID());
    payment.setStatus(Boolean.TRUE.equals(bankResponse.getAuthorized())
      ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED);
    payment.setCardNumberLastFour(lastFour(request.getCardNumber()));
    payment.setExpiryMonth(request.getExpiryMonth());
    payment.setExpiryYear(request.getExpiryYear());
    payment.setCurrency(request.getCurrency());
    payment.setAmount(request.getAmount());

    paymentsRepository.add(payment);
    log.info("Payment {} processed with status {}", payment.getId(), payment.getStatus());
    return payment;
  }

  private static String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null) {
      throw new PaymentRejectedException(
          "Idempotency-Key must contain between 1 and 128 characters");
    }
    String normalizedKey = idempotencyKey.strip();
    if (normalizedKey.isEmpty() || normalizedKey.length() > 128) {
      throw new PaymentRejectedException(
          "Idempotency-Key must contain between 1 and 128 characters");
    }
    return normalizedKey;
  }

  private BankPaymentResponse submitToBank(PostPaymentRequest request) {
    String expiryDate = String.format(Locale.ROOT, "%02d/%d",
      request.getExpiryMonth(), request.getExpiryYear());
    BankPaymentRequest bankRequest = new BankPaymentRequest(request.getCardNumber(), expiryDate,
        request.getCurrency(), request.getAmount(), request.getCvv());
    try {
      BankPaymentResponse body = bankClient.submit(bankRequest);
      if (body == null) {
        throw new PaymentProcessingException("Empty response from acquiring bank", null);
      }
      if (body.getAuthorized() == null) {
        throw new PaymentProcessingException(
            "Acquiring bank response did not include an authorization decision", null);
      }
      return body;
    } catch (RestClientException ex) {
      // Covers non-2xx responses (e.g. the simulator's 503) and connection failures.
      throw new PaymentProcessingException("Acquiring bank failed to process the payment", ex);
    }
  }

  private static String lastFour(String cardNumber) {
    return cardNumber.substring(cardNumber.length() - 4);
  }
}
