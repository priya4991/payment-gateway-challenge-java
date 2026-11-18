package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;

/** Contract for submitting payment requests to the acquiring bank. */
public interface BankClient {

  BankPaymentResponse submit(BankPaymentRequest request);
}
