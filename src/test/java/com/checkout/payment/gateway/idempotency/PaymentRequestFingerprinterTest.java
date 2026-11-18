package com.checkout.payment.gateway.idempotency;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

class PaymentRequestFingerprinterTest {

  private final PaymentRequestFingerprinter fingerprinter = new PaymentRequestFingerprinter();

  @Test
  void whenRequestIsNullThenFingerprintCanStillBeCalculated() {
    assertNotNull(fingerprinter.fingerprint(null));
  }

  @Test
  void whenOptionalRequestFieldsAreNullThenFingerprintCanStillBeCalculated() {
    assertNotNull(fingerprinter.fingerprint(new PostPaymentRequest()));
  }

  @Test
  void whenNullRequestDiffersFromEmptyRequestThenFingerprintsAreDifferent() {
    assertNotEquals(fingerprinter.fingerprint(null),
        fingerprinter.fingerprint(new PostPaymentRequest()));
  }

  @Test
  void whenNullValueDiffersFromLiteralNullThenFingerprintsAreDifferent() {
    PostPaymentRequest missingCard = new PostPaymentRequest();
    PostPaymentRequest literalCard = new PostPaymentRequest();
    literalCard.setCardNumber("null");

    assertNotEquals(fingerprinter.fingerprint(missingCard), fingerprinter.fingerprint(literalCard));
  }

  @Test
  void whenNewlinesShiftFieldBoundariesThenFingerprintsRemainDifferent() {
    PostPaymentRequest first = request("x\n1", 2, 3, "GBP", 100, "123");
    PostPaymentRequest second = request("x", 1, 2, "3\nGBP", 100, "123");

    assertNotEquals(fingerprinter.fingerprint(first), fingerprinter.fingerprint(second));
  }

  @Test
  void whenOnlyCvvChangesThenFingerprintRemainsTheSame() {
    PostPaymentRequest first = request("2222405343248877", 12, 2099, "GBP", 100, "123");
    PostPaymentRequest retry = request("2222405343248877", 12, 2099, "GBP", 100, "987");

    assertEquals(fingerprinter.fingerprint(first), fingerprinter.fingerprint(retry));
  }

  private static PostPaymentRequest request(String cardNumber, int month, int year, String currency,
      int amount, String cvv) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber(cardNumber);
    request.setExpiryMonth(month);
    request.setExpiryYear(year);
    request.setCurrency(currency);
    request.setAmount(amount);
    request.setCvv(cvv);
    return request;
  }
}
