package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** RestTemplate-backed adapter for the acquiring bank simulator. */
@Component
public class RestTemplateBankClient implements BankClient {

  private final RestTemplate restTemplate;
  private final String paymentsUrl;

  public RestTemplateBankClient(RestTemplate restTemplate,
      @Value("${bank.simulator.url}") String bankSimulatorUrl) {
    this.restTemplate = restTemplate;
    this.paymentsUrl = normalizeBaseUrl(bankSimulatorUrl) + "/payments";
  }

  /**
   * Submits a payment to the bank. HTTP errors and connection failures surface as
   * {@link org.springframework.web.client.RestClientException}.
   */
  @Override
  public BankPaymentResponse submit(BankPaymentRequest request) {
    return restTemplate.postForObject(paymentsUrl, request, BankPaymentResponse.class);
  }

  private static String normalizeBaseUrl(String baseUrl) {
    int end = baseUrl.length();
    while (end > 0 && baseUrl.charAt(end - 1) == '/') {
      end--;
    }
    return baseUrl.substring(0, end);
  }
}