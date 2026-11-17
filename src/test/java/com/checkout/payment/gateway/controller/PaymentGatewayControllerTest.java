package com.checkout.payment.gateway.controller;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  PaymentsRepository paymentsRepository;

  @MockBean
  private BankClient bankClient;

  @Test
  void whenPaymentWithIdExistThenCorrectPaymentIsReturned() throws Exception {
    PostPaymentResponse payment = new PostPaymentResponse();
    payment.setId(UUID.randomUUID());
    payment.setAmount(10);
    payment.setCurrency("USD");
    payment.setStatus(PaymentStatus.AUTHORIZED);
    payment.setExpiryMonth(12);
    payment.setExpiryYear(2024);
    payment.setCardNumberLastFour("4321");

    paymentsRepository.add(payment);

    mvc.perform(MockMvcRequestBuilders.get("/payment/" + payment.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(payment.getStatus().getName()))
        .andExpect(jsonPath("$.cardNumberLastFour").value(payment.getCardNumberLastFour()))
        .andExpect(jsonPath("$.expiryMonth").value(payment.getExpiryMonth()))
        .andExpect(jsonPath("$.expiryYear").value(payment.getExpiryYear()))
        .andExpect(jsonPath("$.currency").value(payment.getCurrency()))
        .andExpect(jsonPath("$.amount").value(payment.getAmount()));
  }

  @Test
  void whenPaymentWithIdDoesNotExistThen404IsReturned() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/payment/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Page not found"));
  }

  @Test
  void whenValidPaymentIsPostedThenPaymentResponseIsReturned() throws Exception {
    when(bankClient.submit(any(BankPaymentRequest.class))).thenReturn(bankResponse(true));
    String idempotencyKey = UUID.randomUUID().toString();

    String responseBody = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.cardNumberLastFour").value("8877"))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(100))
        .andReturn().getResponse().getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(responseBody.contains("2222405343248877"));
    org.junit.jupiter.api.Assertions.assertFalse(responseBody.contains("\"cvv\""));
    verify(bankClient).submit(any(BankPaymentRequest.class));
  }

  @Test
  void whenRequestBodyIsJsonNullThenBadRequestIsReturnedWithoutCallingBank() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("null"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenRequestBodyIsMalformedJsonThenBadRequestIsReturnedWithoutCallingBank() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenPostPaymentHasNoIdempotencyKeyThenBadRequestIsReturned() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest()))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenSamePaymentIsPostedTwiceWithSameKeyThenBankIsCalledOnce() throws Exception {
    when(bankClient.submit(any(BankPaymentRequest.class))).thenReturn(bankResponse(true));
    String idempotencyKey = UUID.randomUUID().toString();
    String body = validPaymentRequest();

    String firstResponse = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String retryResponse = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertEquals(jsonValue(firstResponse, "id"), jsonValue(retryResponse, "id"));
    verify(bankClient, times(1)).submit(any(BankPaymentRequest.class));
  }

  @Test
  void whenAmountIsZeroThenBadRequestIsReturnedWithoutCallingBank() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest().replace("\"amount\": 100", "\"amount\": 0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("amount must be greater than zero"));

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenAmountIsFractionalThenBadRequestIsReturnedWithoutCallingBank() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest().replace("\"amount\": 100", "\"amount\": 100.9")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("amount must be a whole number"));

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenExpiryYearIsOutOfRangeThenBadRequestIsReturnedWithoutCallingBank() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest().replace("2099", "2147483647")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("expiry_year must be between 1 and 9999"));

    verifyNoInteractions(bankClient);
  }

  @Test
  void whenIdempotencyKeyIsReusedForDifferentRequestThenConflictIsReturned() throws Exception {
    when(bankClient.submit(any(BankPaymentRequest.class))).thenReturn(bankResponse(true));
    String idempotencyKey = UUID.randomUUID().toString();

    mvc.perform(MockMvcRequestBuilders.post("/payment")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validPaymentRequest()));

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest().replace("\"amount\": 100", "\"amount\": 200")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.message")
            .value("This Idempotency-Key was already used with a different request"));

    verify(bankClient, times(1)).submit(any(BankPaymentRequest.class));
  }

  @Test
  void whenBankFailsThenServiceUnavailableIsReturned() throws Exception {
    when(bankClient.submit(any(BankPaymentRequest.class)))
        .thenThrow(new RestClientException("Bank unavailable"));

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPaymentRequest()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Payment could not be processed"));
  }

  private static String validPaymentRequest() {
    return """
        {
          "card_number": "2222405343248877",
          "expiry_month": 12,
          "expiry_year": 2099,
          "currency": "GBP",
          "amount": 100,
          "cvv": "123"
        }
        """;
  }

  private static BankPaymentResponse bankResponse(boolean authorized) {
    BankPaymentResponse response = new BankPaymentResponse();
    response.setAuthorized(authorized);
    response.setAuthorizationCode(authorized ? "auth-code" : "");
    return response;
  }

  private static String jsonValue(String json, String field) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asText();
  }
}
