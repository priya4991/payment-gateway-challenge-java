package com.checkout.payment.gateway.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class ApplicationConfigurationTest {

  @Test
  void whenRestTemplateBeanIsCreatedThenTenSecondTimeoutsAreConfigured() {
    RestTemplate restTemplate = new ApplicationConfiguration()
      .restTemplate(new RestTemplateBuilder());

    Object configuredRequestFactory = Objects.requireNonNull(restTemplate.getRequestFactory());
    assertInstanceOf(SimpleClientHttpRequestFactory.class, configuredRequestFactory);
    assertEquals(10000, ReflectionTestUtils.getField(configuredRequestFactory, "connectTimeout"));
    assertEquals(10000, ReflectionTestUtils.getField(configuredRequestFactory, "readTimeout"));
  }
}
