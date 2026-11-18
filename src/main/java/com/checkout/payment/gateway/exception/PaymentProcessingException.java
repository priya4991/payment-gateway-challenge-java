package com.checkout.payment.gateway.exception;

/** Thrown when the acquiring bank cannot process a valid payment request. */
public class PaymentProcessingException extends RuntimeException {
  public PaymentProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
