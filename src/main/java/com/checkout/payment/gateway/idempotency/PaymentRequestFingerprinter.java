package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Creates a process-local keyed fingerprint without retaining card data in the store. */
@Component
public class PaymentRequestFingerprinter {

  private final SecretKeySpec secretKey;

  public PaymentRequestFingerprinter() {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    this.secretKey = new SecretKeySpec(secret, "HmacSHA256");
  }

  public String fingerprint(PostPaymentRequest request) {
    StringBuilder canonicalRequest = new StringBuilder();
    appendField(canonicalRequest, "payment-request-v1");
    if (request == null) {
      appendField(canonicalRequest, null);
    } else {
      appendField(canonicalRequest, request.getCardNumber());
      appendField(canonicalRequest,
          request.getExpiryMonth() == null ? null : request.getExpiryMonth().toString());
      appendField(canonicalRequest,
          request.getExpiryYear() == null ? null : request.getExpiryYear().toString());
      appendField(canonicalRequest, request.getCurrency());
      appendField(canonicalRequest,
          request.getAmount() == null ? null : request.getAmount().toString());
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(secretKey);
      return HexFormat.of().formatHex(
          mac.doFinal(canonicalRequest.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Unable to fingerprint payment request", ex);
    }
  }

  private static void appendField(StringBuilder canonicalRequest, String value) {
    if (value == null) {
      canonicalRequest.append("-1:");
      return;
    }
    canonicalRequest.append(value.length()).append(':').append(value);
  }
}