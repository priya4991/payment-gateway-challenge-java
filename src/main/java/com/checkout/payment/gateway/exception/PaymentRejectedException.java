package com.checkout.payment.gateway.exception;

/** Thrown when a payment request fails validation and must not be sent to the bank. */
public class PaymentRejectedException extends RuntimeException {
  public PaymentRejectedException(String message) {
    super(message);
  }
}
