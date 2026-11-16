package com.checkout.payment.gateway.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankClientTest {

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:8080", "http://localhost:8080/"})
  void whenSubmittingPaymentThenRestTemplatePostsToBankPaymentsEndpoint(String bankUrl) {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    server.expect(requestTo("http://localhost:8080/payments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"authorized\":true,\"authorization_code\":\"abc\"}",
            MediaType.APPLICATION_JSON));
    BankClient bankClient = new RestTemplateBankClient(restTemplate, bankUrl);
    BankPaymentRequest request = new BankPaymentRequest(
        "2222405343248877", "12/2099", "GBP", 100, "123");

    BankPaymentResponse response = bankClient.submit(request);

    assertTrue(response.isAuthorized());
    server.verify();
  }
}
