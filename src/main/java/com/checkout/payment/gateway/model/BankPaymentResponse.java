package com.checkout.payment.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response returned by the bank simulator for a successfully received payment request. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BankPaymentResponse {

  private Boolean authorized;
  @JsonProperty("authorization_code")
  private String authorizationCode;

  public Boolean getAuthorized() {
    return authorized;
  }

  public void setAuthorized(Boolean authorized) {
    this.authorized = authorized;
  }

  public String getAuthorizationCode() {
    return authorizationCode;
  }

  public void setAuthorizationCode(String authorizationCode) {
    this.authorizationCode = authorizationCode;
  }
}
