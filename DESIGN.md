# Payment Gateway – Design Notes

## Running
1. Start the bank simulator: `docker-compose up` (or `docker compose up`).
2. Start the gateway: `./gradlew bootRun` (listens on port 8090).
3. Run tests: `./gradlew test`.
4. Generate coverage reports: `./gradlew jacocoTestReport`.

The HTML coverage report is written to `build/reports/jacoco/test/html/index.html`; the XML report is
`build/reports/jacoco/test/jacocoTestReport.xml`. The JaCoCo report excludes the model package.

## Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/payment` | Process a payment. Requires an `Idempotency-Key` header. Returns 200 with the payment (`Authorized` or `Declined`). |
| GET | `/payment/{id}` | Retrieve a previously processed payment. Returns 404 if unknown. |

The POST JSON body fields are `card_number`, `expiry_month`, `expiry_year`, `currency`, `amount`, and `cvv`.
Responses for validation errors use **400**, idempotency conflicts use **409**, and bank processing failures
use **503**. Malformed JSON and fields with incorrect JSON types return **400** with a safe `ErrorResponse`;
fractional values for `amount` specifically report that the amount must be a whole number. Missing required
headers are rejected by Spring with **400**.

## Validation and rejection
The request is validated before any bank call. Validation failures return **400** with all violations listed
in the `message` field, and the bank is not called. Checks:
- Card number: required, 14–19 characters, digits only.
- Expiry month: required, 1–12. Expiry year: required, between 1 and 9999. The month/year pair must be in the future.
- Currency: required and one of the configured supported currencies (`GBP`, `USD`, `EUR`). Input is trimmed
  and uppercased before checking; this is an allow-list, not a general ISO 4217 validator.
- Amount: required positive integer (minor units); fractional JSON numbers are rejected during request binding.
- CVV: required, 3–4 digits.

## Bank integration
- The gateway POSTs to the simulator at `bank.simulator.url` (default `http://localhost:8080/payments`).
- `BankClient` is the service-facing abstraction; `RestTemplateBankClient` implements it using the configured
  `RestTemplate` bean from `ApplicationConfiguration`.
- Bank calls use 10-second connect and read timeouts. HTTP error responses and connection failures are surfaced
  as Spring `RestClientException`s and translated to `PaymentProcessingException`.
- The bank's `authorized` flag maps to `Authorized` or `Declined`.
- A null/empty bank response or one missing the `authorized` decision is treated as a processing failure;
  an explicit `authorized: false` is recorded as a decline.
- If the bank returns an error (for example the simulator's 503 for card numbers ending in 0) or cannot be reached,
  the gateway returns **503** and the payment is not recorded because it has no final status. The idempotency key
  is retained as failed to prevent an unsafe retry with the same key.

## Idempotency
- `POST /payment` requires a non-blank `Idempotency-Key` whose trimmed value is no longer than 128 characters.
  Repeating the same normalized key and request returns the original payment; reusing it for a different request
  or while its first request is in progress returns **409**.
- The service first performs a non-mutating lookup for an existing key. This allows a completed payment to be
  replayed even if a time-dependent rule, such as card expiry, would reject the request today. If no entry exists,
  validation runs before the key is atomically claimed, so invalid requests do not reserve keys. Concurrent
  requests still contend through the atomic claim operation.
- A failed bank attempt retains the key in a failed state. Retrying that key returns **503** without another bank
  submission; a new attempt needs a new key.
- Request matching uses a null-safe, length-prefixed HMAC fingerprint, not a saved copy of the request. The HMAC
  secret is generated at process startup, and raw card data/CVV are not stored in idempotency records.
- `InMemoryIdempotencyStore` is process-local and unbounded: state is lost on restart, is not shared across
  instances, and has no expiry policy. It is for this exercise, not production. Production should use durable,
  shared storage with atomic uniqueness and a retention policy.
- The bank simulator does not accept the idempotency key. If a network failure occurs after the bank may have
  processed the request, the gateway cannot determine the outcome; production should propagate the key to the bank
  or provide reconciliation for uncertain transactions.

## Data handling
- The payment repository stores the response, including only the last four card digits; the full card number and
  CVV are not persisted by the gateway. The POST response likewise omits them. `PostPaymentRequest.toString()`
  explicitly excludes both values.
- The last four digits are stored as a `String` so leading zeros are preserved. The request necessarily carries
  full card data and CVV transiently to the bank adapter.

## Assumptions
- `PaymentsRepository` uses an in-memory `ConcurrentHashMap`, as allowed by the brief. Payment records disappear
  on restart and are local to one process.
- Expiry validity is checked at the month level (the card is valid through the end of its expiry month), using
  an injected UTC `Clock`. Service tests use a fixed clock to make month-boundary checks deterministic.

## Tests
- `PaymentGatewayServiceTest` covers authorization/decline, request forwarding, currency normalization, required
  and malformed fields, non-positive amounts, expiry boundaries with a fixed clock, idempotency replay after
  expiry, validation failures without key claims, normalized key boundaries, concurrent duplicate submissions,
  and bank failures.
- `PaymentRequestFingerprinterTest` covers null/missing fields and delimiter-safe canonical fingerprints.
- `PaymentGatewayControllerTest` covers GET success/not-found and POST success, request-body failures, required
  idempotency headers, duplicate/conflicting keys, validation errors, bank failures, and card-data redaction.
- `BankClientTest` verifies the RestTemplate adapter sends a POST to the bank's `/payments` endpoint and parses
  its response. `ApplicationConfigurationTest` verifies the configured request factory has 10-second connect
  and read timeouts; it does not wait for real network timeouts.
