package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.exception.PaymentRejectedException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates payment requests against the rules in README2.md before any bank call. */
@Component
public class PaymentRequestValidator {

  private final Set<String> supportedCurrencies;
  private final Clock clock;

  public PaymentRequestValidator(@Value("${payment.supported-currencies}") List<String> supportedCurrencies,
      Clock clock) {
    this.supportedCurrencies = supportedCurrencies.stream()
        .map(code -> code.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
    this.clock = clock;
  }

  /**
   * Throws {@link PaymentRejectedException} listing every violation, if there are any.
   */
  public void validate(PostPaymentRequest request) {
    if (request == null) {
      throw new PaymentRejectedException("request body is required");
    }
    List<String> errors = collectErrors(request);
    if (!errors.isEmpty()) {
      throw new PaymentRejectedException(String.join("; ", errors));
    }
  }

  private List<String> collectErrors(PostPaymentRequest request) {
    List<String> errors = new ArrayList<>();

    String card = request.getCardNumber();
    if (card == null) {
      errors.add("card_number is required");
    } else if (card.length() < 14 || card.length() > 19) {
      errors.add("card_number must be between 14 and 19 characters");
    } else if (!isNumeric(card)) {
      errors.add("card_number must only contain numeric characters");
    }

    Integer month = request.getExpiryMonth();
    Integer year = request.getExpiryYear();
    if (month == null) {
      errors.add("expiry_month is required");
    } else if (month < 1 || month > 12) {
      errors.add("expiry_month must be between 1 and 12");
    }
    if (year == null) {
      errors.add("expiry_year is required");
    } else if (year < 1 || year > 9999) {
      errors.add("expiry_year must be between 1 and 9999");
    }
    if (month != null && year != null && month >= 1 && month <= 12
        && year >= 1 && year <= 9999
        && !YearMonth.of(year, month).isAfter(YearMonth.now(clock))) {
      errors.add("expiry date must be in the future");
    }

    String currency = request.getCurrency();
    if (currency == null) {
      errors.add("currency is required");
    } else if (!supportedCurrencies.contains(currency)) {
      errors.add("currency must be one of " + supportedCurrencies);
    }

    if (request.getAmount() == null) {
      errors.add("amount is required");
    } else if (request.getAmount() <= 0) {
      errors.add("amount must be greater than zero");
    }

    String cvv = request.getCvv();
    if (cvv == null) {
      errors.add("cvv is required");
    } else if (cvv.length() < 3 || cvv.length() > 4) {
      errors.add("cvv must be 3 or 4 characters long");
    } else if (!isNumeric(cvv)) {
      errors.add("cvv must only contain numeric characters");
    }

    return errors;
  }

  private static boolean isNumeric(String value) {
    return value.matches("[0-9]+");
  }
}
