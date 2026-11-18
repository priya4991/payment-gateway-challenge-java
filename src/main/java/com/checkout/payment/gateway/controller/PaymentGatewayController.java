package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.model.ErrorResponse;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController("api")
public class PaymentGatewayController {

  private final PaymentGatewayService paymentGatewayService;

  public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
    this.paymentGatewayService = paymentGatewayService;
  }

  @PostMapping("/payment")
    @Operation(summary = "Process a payment", description = "Submits a payment to the acquiring bank. "
      + "Reuse the same Idempotency-Key only when retrying the same payment request.")
    @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Payment authorized or declined",
        content = @Content(schema = @Schema(implementation = PostPaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation or request-body format error "
          + "(ErrorResponse); a missing required header uses Spring's default 400 response",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
      @ApiResponse(responseCode = "409", description = "Idempotency key conflict",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Acquiring bank processing failure",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
  public ResponseEntity<PostPaymentResponse> postPayment(@RequestBody PostPaymentRequest request,
      @Parameter(description = "Unique key for this payment; reuse only for retries of the same request",
        required = true)
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return new ResponseEntity<>(paymentGatewayService.processPayment(request, idempotencyKey),
        HttpStatus.OK);
  }

  @GetMapping("/payment/{id}")
    @Operation(summary = "Retrieve a payment", description = "Returns a previously processed payment by ID.")
    @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Payment found",
        content = @Content(schema = @Schema(implementation = PostPaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Payment ID is not a valid UUID "
          + "(Spring's default 400 response)"),
      @ApiResponse(responseCode = "404", description = "Payment not found",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
  public ResponseEntity<PostPaymentResponse> getPostPaymentEventById(@PathVariable UUID id) {
    return new ResponseEntity<>(paymentGatewayService.getPaymentById(id), HttpStatus.OK);
  }
}
