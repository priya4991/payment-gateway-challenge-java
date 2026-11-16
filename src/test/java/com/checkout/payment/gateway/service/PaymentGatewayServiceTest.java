package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.PaymentProcessingException;
import com.checkout.payment.gateway.exception.PaymentRejectedException;
import com.checkout.payment.gateway.idempotency.InMemoryIdempotencyStore;
import com.checkout.payment.gateway.idempotency.IdempotencyStore;
import com.checkout.payment.gateway.idempotency.PaymentRequestFingerprinter;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.PaymentRequestValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class PaymentGatewayServiceTest {

  private static final Clock FIXED_CLOCK = Clock.fixed(
      Instant.parse("2025-06-15T12:00:00Z"), ZoneOffset.UTC);

  private PaymentsRepository repository;
  private BankClient bankClient;
  private PaymentGatewayService service;

  @BeforeEach
  void setUp() {
    repository = new PaymentsRepository();
    bankClient = mock(BankClient.class);
    service = new PaymentGatewayService(repository,
      new PaymentRequestValidator(List.of("GBP", "USD", "EUR"), FIXED_CLOCK), bankClient,
      new InMemoryIdempotencyStore(), new PaymentRequestFingerprinter());
  }

  @Test
  void whenBankAuthorizesThenPaymentIsAuthorizedAndStored() {
    when(bankClient.submit(any())).thenReturn(authorized(true));

    PostPaymentResponse response = processPayment(validRequest("2222405343248877"));

    assertEquals(PaymentStatus.AUTHORIZED, response.getStatus());
    assertEquals("8877", response.getCardNumberLastFour());
    assertEquals(response.getId(), repository.get(response.getId()).orElseThrow().getId());
  }

  @Test
  void whenBankDeclinesThenPaymentIsDeclined() {
    when(bankClient.submit(any())).thenReturn(authorized(false));

    PostPaymentResponse response = processPayment(validRequest("2222405343248882"));

    assertEquals(PaymentStatus.DECLINED, response.getStatus());
  }

  @Test
  void whenBankRequestIsBuiltThenExpiryAndCardAreForwarded() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryMonth(4);

    processPayment(request);

    org.mockito.ArgumentCaptor<BankPaymentRequest> captor =
        org.mockito.ArgumentCaptor.forClass(BankPaymentRequest.class);
    verify(bankClient).submit(captor.capture());
    assertEquals("2222405343248877", captor.getValue().getCardNumber());
    assertEquals("04/2099", captor.getValue().getExpiryDate());
    assertEquals("GBP", captor.getValue().getCurrency());
    assertEquals(100, captor.getValue().getAmount());
    assertEquals("123", captor.getValue().getCvv());
  }

  @Test
  void whenCurrencyIsLowerCaseThenNormalisedToUpperCase() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setCurrency("gbp");

    PostPaymentResponse response = processPayment(request);

    assertEquals("GBP", response.getCurrency());
  }

  @Test
  void whenCurrencyIsNotAllowedThenRejectedWithoutCallingBank() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setCurrency("JPY");

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenRequestIsInvalidThenRejectedWithoutCallingBank() {
    PostPaymentRequest request = validRequest("12345");
    request.setCvv("1");

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenRequiredFieldsAreMissingThenAllViolationsAreReported() {
    PaymentRejectedException exception = assertThrows(PaymentRejectedException.class,
        () -> processPayment(new PostPaymentRequest()));

    assertTrue(exception.getMessage().contains("card_number is required"));
    assertTrue(exception.getMessage().contains("expiry_month is required"));
    assertTrue(exception.getMessage().contains("expiry_year is required"));
    assertTrue(exception.getMessage().contains("currency is required"));
    assertTrue(exception.getMessage().contains("amount is required"));
    assertTrue(exception.getMessage().contains("cvv is required"));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenRequestBodyIsNullThenItIsRejectedWithoutUnexpectedException() {
    PaymentRejectedException exception = assertThrows(PaymentRejectedException.class,
        () -> service.processPayment(null, "retry-key"));

    assertEquals("request body is required", exception.getMessage());
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenCardMonthAndCvvAreMalformedThenEachViolationIsReported() {
    PostPaymentRequest request = validRequest("22224053432488AB");
    request.setExpiryMonth(13);
    request.setCvv("12A");

    PaymentRejectedException exception = assertThrows(PaymentRejectedException.class,
        () -> processPayment(request));

    assertTrue(exception.getMessage().contains("card_number must only contain numeric characters"));
    assertTrue(exception.getMessage().contains("expiry_month must be between 1 and 12"));
    assertTrue(exception.getMessage().contains("cvv must only contain numeric characters"));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenIdempotencyKeyIsAlreadyInProgressThenConflictIsThrownWithoutCallingBank() {
    IdempotencyStore inProgressStore = mock(IdempotencyStore.class);
    when(inProgressStore.lookup(anyString(), anyString())).thenReturn(
      new IdempotencyStore.Claim(IdempotencyStore.Status.NEW, null));
    when(inProgressStore.claim(anyString(), anyString())).thenReturn(
        new IdempotencyStore.Claim(IdempotencyStore.Status.IN_PROGRESS, null));
    PaymentGatewayService inProgressService = new PaymentGatewayService(repository,
      new PaymentRequestValidator(List.of("GBP", "USD", "EUR"), FIXED_CLOCK), bankClient,
        inProgressStore, new PaymentRequestFingerprinter());

    assertThrows(IdempotencyConflictException.class,
        () -> inProgressService.processPayment(validRequest("2222405343248877"), "retry-key"));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenIdempotencyKeyIsBlankOrTooLongThenRequestIsRejected() {
    PostPaymentRequest request = validRequest("2222405343248877");

    assertThrows(PaymentRejectedException.class, () -> service.processPayment(request, "  "));
    assertThrows(PaymentRejectedException.class, () -> service.processPayment(request, null));
    assertThrows(PaymentRejectedException.class,
        () -> service.processPayment(request, "k".repeat(129)));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenNormalizedIdempotencyKeyIsAtLimitThenPaddedAndUnpaddedKeysReplaySamePayment() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest request = validRequest("2222405343248877");
    String key = "k".repeat(128);

    PostPaymentResponse first = service.processPayment(request, " " + key + " ");
    PostPaymentResponse retry = service.processPayment(request, key);

    assertEquals(first.getId(), retry.getId());
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenValidationFailsThenKeyRemainsAvailableForCorrectedRequest() {
    PostPaymentRequest invalidRequest = validRequest("2222405343248877");
    invalidRequest.setAmount(0);

    assertThrows(PaymentRejectedException.class,
        () -> service.processPayment(invalidRequest, "reusable-key"));

    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentResponse response = service.processPayment(
        validRequest("2222405343248877"), "reusable-key");

    assertEquals(PaymentStatus.AUTHORIZED, response.getStatus());
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenAmountIsZeroThenRejectedWithoutCallingBank() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setAmount(0);

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenAmountIsNegativeThenRejectedWithoutCallingBank() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setAmount(-1);

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenExpiryIsInThePastThenRejected() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryMonth(1);
    request.setExpiryYear(2000);

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenExpiryIsCurrentMonthThenRejectedUsingFixedClock() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryMonth(6);
    request.setExpiryYear(2025);

    assertThrows(PaymentRejectedException.class, () -> processPayment(request));
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenExpiryIsNextMonthThenAcceptedUsingFixedClock() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryMonth(7);
    request.setExpiryYear(2025);

    PostPaymentResponse response = processPayment(request);

    assertEquals(PaymentStatus.AUTHORIZED, response.getStatus());
    verify(bankClient).submit(any());
  }

  @Test
  void whenExpiryYearIsOutsideSupportedRangeThenRejectedWithoutCallingBank() {
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryYear(Integer.MAX_VALUE);

    PaymentRejectedException exception = assertThrows(PaymentRejectedException.class,
        () -> processPayment(request));

    assertEquals("expiry_year must be between 1 and 9999", exception.getMessage());
    verifyNoInteractions(bankClient);
  }

  @Test
  void whenBankReturnsErrorThenProcessingExceptionIsThrown() {
    RestClientException bankError = new RestClientException("Bank returned HTTP 503");
    when(bankClient.submit(any())).thenThrow(bankError);

    assertThrows(PaymentProcessingException.class,
      () -> processPayment(validRequest("2222405343248880")));
  }

  @Test
  void whenBankReturnsEmptyBodyThenProcessingExceptionIsThrown() {
    when(bankClient.submit(any())).thenReturn(null);

    assertThrows(PaymentProcessingException.class,
        () -> processPayment(validRequest("2222405343248877")));
  }

  @Test
  void whenSameKeyAndRequestAreSubmittedAgainThenOriginalPaymentIsReturned() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest request = validRequest("2222405343248877");

    PostPaymentResponse first = service.processPayment(request, "retry-key");
    PostPaymentResponse retry = service.processPayment(request, "retry-key");

    assertEquals(first.getId(), retry.getId());
    assertEquals(first.getStatus(), retry.getStatus());
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenCompletedPaymentExpiresLaterThenSameRequestStillReplaysOriginalResult() {
    IdempotencyStore store = new InMemoryIdempotencyStore();
    PaymentRequestFingerprinter fingerprinter = new PaymentRequestFingerprinter();
    PostPaymentRequest request = validRequest("2222405343248877");
    request.setExpiryMonth(7);
    request.setExpiryYear(2025);
    when(bankClient.submit(any())).thenReturn(authorized(true));

    PaymentGatewayService beforeExpiry = new PaymentGatewayService(repository,
        new PaymentRequestValidator(List.of("GBP", "USD", "EUR"), FIXED_CLOCK), bankClient,
        store, fingerprinter);
    PostPaymentResponse original = beforeExpiry.processPayment(request, "expiry-replay-key");

    Clock afterExpiryClock = Clock.fixed(Instant.parse("2025-08-15T12:00:00Z"), ZoneOffset.UTC);
    PaymentGatewayService afterExpiry = new PaymentGatewayService(repository,
        new PaymentRequestValidator(List.of("GBP", "USD", "EUR"), afterExpiryClock), bankClient,
        store, fingerprinter);
    PostPaymentResponse replay = afterExpiry.processPayment(request, "expiry-replay-key");

    assertEquals(original.getId(), replay.getId());
    assertEquals(PaymentStatus.AUTHORIZED, replay.getStatus());
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenTwentyThreadsSubmitSameKeyConcurrentlyThenBankIsCalledOnce() throws Exception {
    int threadCount = 20;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch bankCallStarted = new CountDownLatch(1);
    CountDownLatch releaseBankCall = new CountDownLatch(1);
    CountDownLatch conflicts = new CountDownLatch(threadCount - 1);
    when(bankClient.submit(any())).thenAnswer(invocation -> {
      bankCallStarted.countDown();
      await(releaseBankCall);
      return authorized(true);
    });

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<PostPaymentResponse>> results = new ArrayList<>();
    PostPaymentRequest request = validRequest("2222405343248877");
    try {
      for (int i = 0; i < threadCount; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          await(start);
          try {
            return service.processPayment(request, "shared-retry-key");
          } catch (IdempotencyConflictException ex) {
            conflicts.countDown();
            return null;
          }
        }));
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS), "All workers should be ready");
      start.countDown();
      assertTrue(bankCallStarted.await(5, TimeUnit.SECONDS), "One request should reach the bank");
      assertTrue(conflicts.await(5, TimeUnit.SECONDS), "Other requests should conflict in progress");
      releaseBankCall.countDown();

      int completedRequests = 0;
      for (Future<PostPaymentResponse> result : results) {
        PostPaymentResponse response = result.get(5, TimeUnit.SECONDS);
        if (response != null) {
          completedRequests++;
          assertEquals(PaymentStatus.AUTHORIZED, response.getStatus());
          assertTrue(repository.get(response.getId()).isPresent());
        }
      }
      assertEquals(1, completedRequests);
      verify(bankClient, times(1)).submit(any());
    } finally {
      releaseBankCall.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void whenKeyIsReusedWithDifferentRequestThenConflictIsThrown() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    PostPaymentRequest firstRequest = validRequest("2222405343248877");
    PostPaymentRequest changedRequest = validRequest("2222405343248877");
    changedRequest.setAmount(200);

    service.processPayment(firstRequest, "retry-key");

    assertThrows(IdempotencyConflictException.class,
        () -> service.processPayment(changedRequest, "retry-key"));
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenCompletedKeyIsReusedWithInvalidDifferentRequestThenConflictIsThrown() {
    when(bankClient.submit(any())).thenReturn(authorized(true));
    service.processPayment(validRequest("2222405343248877"), "retry-key");
    PostPaymentRequest invalidChangedRequest = validRequest("2222405343248877");
    invalidChangedRequest.setAmount(0);

    assertThrows(IdempotencyConflictException.class,
        () -> service.processPayment(invalidChangedRequest, "retry-key"));
    verify(bankClient, times(1)).submit(any());
  }

  @Test
  void whenBankCallFailsThenRetryWithSameKeyDoesNotCallBankAgain() {
    RestClientException bankError = new RestClientException("Bank returned HTTP 503");
    when(bankClient.submit(any())).thenThrow(bankError);
    PostPaymentRequest request = validRequest("2222405343248880");

    assertThrows(PaymentProcessingException.class,
        () -> service.processPayment(request, "retry-key"));
    assertThrows(PaymentProcessingException.class,
        () -> service.processPayment(request, "retry-key"));
    verify(bankClient, times(1)).submit(any());
  }

  private static BankPaymentResponse authorized(boolean authorized) {
    BankPaymentResponse response = new BankPaymentResponse();
    response.setAuthorized(authorized);
    response.setAuthorizationCode(authorized ? "abc" : "");
    return response;
  }

  private static PostPaymentRequest validRequest(String cardNumber) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber(cardNumber);
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency("GBP");
    request.setAmount(100);
    request.setCvv("123");
    return request;
  }

  private PostPaymentResponse processPayment(PostPaymentRequest request) {
    return service.processPayment(request, UUID.randomUUID().toString());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent test coordination");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Concurrent test was interrupted", ex);
    }
  }
}
