package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
@Slf4j
public class CommonExceptionHandler {

  @ExceptionHandler(EventProcessingException.class)
  public ResponseEntity<ErrorResponse> handleException(EventProcessingException ex) {
    log.error("Exception happened", ex);
    return new ResponseEntity<>(new ErrorResponse("Page not found"),
        HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(PaymentRejectedException.class)
  public ResponseEntity<ErrorResponse> handleRejected(PaymentRejectedException ex) {
    return new ResponseEntity<>(new ErrorResponse(ex.getMessage()), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException ex) {
    String message = isFractionalAmount(ex)
        ? "amount must be a whole number"
        : "Request body is invalid or contains a field with an incorrect type";
    return new ResponseEntity<>(new ErrorResponse(message), HttpStatus.BAD_REQUEST);
  }

  private static boolean isFractionalAmount(HttpMessageNotReadableException ex) {
    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof JsonMappingException mappingException) {
        boolean amountField = mappingException.getPath().stream()
            .anyMatch(reference -> "amount".equals(reference.getFieldName()));
        return amountField && mappingException.getOriginalMessage().contains("Floating-point value");
      }
      cause = cause.getCause();
    }
    return false;
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
    return new ResponseEntity<>(new ErrorResponse(ex.getMessage()), HttpStatus.CONFLICT);
  }

  @ExceptionHandler(PaymentProcessingException.class)
  public ResponseEntity<ErrorResponse> handleProcessingFailure(PaymentProcessingException ex) {
    log.error("Payment processing failed", ex);
    return new ResponseEntity<>(new ErrorResponse("Payment could not be processed"),
        HttpStatus.SERVICE_UNAVAILABLE);
  }
}
