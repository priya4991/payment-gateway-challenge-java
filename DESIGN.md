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

## Production limitations and considerations

The following are limitations of this exercise implementation, not capabilities currently provided:

| Current implementation | Production implication | Evolution |
|---|---|---|
| Payments are stored in the process-local `ConcurrentHashMap` in `PaymentsRepository`. | Restarting the process loses payment history, and replicas do not share records. | Use a durable relational database with appropriate transaction boundaries, schema migrations, backups, and retention controls. |
| `InMemoryIdempotencyStore` is process-local, unbounded, and has no expiry policy. | Restarts lose replay protection; different replicas can independently accept the same key; memory use grows with requests. | Persist idempotency records in shared durable storage, atomically claim keys with a unique constraint, and define retention. Deploy multiple replicas only after payment and idempotency state are externalized. |
| The gateway makes a synchronous `RestTemplate` call to the acquiring bank with 10-second connect/read timeouts. | A slow bank occupies request threads. A timeout does not prove whether the bank processed the payment; the current code conservatively fails the idempotency key and does not automatically retry, but cannot resolve an ambiguous outcome. | First use the bank's idempotency support if available; otherwise add a reconciliation process and explicit handling for unknown outcomes. Keep bounded timeouts and do not blindly retry payment submissions. |
| PAN and CVV are not persisted by the gateway, but are present transiently in the incoming request and outbound bank request. The configured simulator URL is plain HTTP on localhost. No API authentication, TLS termination, or rate limiting is configured in this application. | A production deployment exposed beyond a trusted local environment needs controls for protecting cardholder data and preventing unauthorized or abusive access. | Apply the relevant PCI/security requirements: terminate TLS at a controlled ingress/API gateway, authenticate and authorize merchants, restrict network paths, use tokenization where supported, minimize retention, manage secrets outside source/config maps, and ensure logs never include PAN, CVV, or raw request bodies. |
| Logging consists of payment ID/status messages and exception stack traces in the controller advice. There are no application metrics, tracing, health endpoints, or alert rules configured. | Operators cannot readily quantify outcomes, localize latency, correlate a request across components, or detect saturation and degradation. Error causes may include downstream diagnostics and must be reviewed before production logging. | Add the observability controls described below, with sensitive-data filtering and bounded metric dimensions. |
| The repository has no gateway Dockerfile or Kubernetes resources; `docker-compose.yml` runs only the bank simulator. | There is no packaged gateway image, deployment strategy, health probing, or orchestration configuration in this repository. | Containerize and deploy using the proposed Kubernetes approach below; those artifacts are future work, not implemented here. |

The current synchronous Spring MVC service and a relational database are a reasonable first production architecture. An asynchronous workflow or broker is not required by the current traffic model; introduce one only if measured throughput, isolation, or workflow requirements justify its operational cost.

## Proposed production observability

This section describes future instrumentation; Micrometer, Prometheus, OpenTelemetry, alerting, and production health endpoints are **not currently configured**.

### Metrics

Use Micrometer as the application instrumentation API and export metrics to a backend such as Prometheus. Record:

- Payment request counts by bounded outcome (authorized, declined, rejected, processing error), plus total processing latency.
- Acquiring-bank request latency, result category, timeout/error count, and downstream HTTP status where available.
- Idempotency replay, conflict, and in-progress counts.
- Application error rates and JVM/process/container signals such as request-thread use, CPU, memory, and connection-pool saturation.

Keep labels low-cardinality: do not use payment IDs, idempotency keys, card data, or other per-request values as metric labels. These signals answer whether payment outcomes or bank calls are degrading, whether retries are replaying/conflicting as expected, and whether the service is approaching resource limits.

### Logs, traces, and alerts

- Emit structured logs with timestamp, severity, service/version, correlation or trace ID, payment ID when available, failure category, and downstream status. Current logs are basic SLF4J messages; production logging is not structured or correlated. Never log PAN, CVV, raw payment payloads, idempotency keys, authorization codes, or unfiltered downstream response bodies. Review exception logging so upstream diagnostics cannot expose sensitive data.
- Use OpenTelemetry tracing to connect the inbound HTTP request, validation/idempotency decision, and outbound acquiring-bank call. Traces help distinguish gateway time from downstream latency and correlate a failed payment across services; do not attach sensitive request fields or high-cardinality payment data as searchable attributes.
- Alert on elevated gateway 5xx/error rates, bank timeout/latency/failure increases, unusual authorization-rate changes, and loss of application availability or healthy capacity. Correlation with request volume and resource saturation helps distinguish a bank outage from a gateway overload or deployment regression.

## Proposed Kubernetes deployment and operation

No gateway container image, Kubernetes Deployment/Service, Ingress, probe endpoints, or cluster configuration is included in this repository. The following is a proposed deployment approach, not an implemented feature.

- Package the Spring Boot application as a container image. Run it behind a Kubernetes `Service`, with an `Ingress` or API gateway handling external routing and TLS. Apply authentication and authorization at the appropriate gateway/application boundary.
- Put non-sensitive settings such as port and supported currencies in a `ConfigMap`. Put credentials and cryptographic configuration in Kubernetes `Secrets` or, preferably for production, an external secrets manager with rotation and access controls. Do not place secrets in an image, source control, or ConfigMap.
- Move payment and idempotency state to a shared relational database before scaling beyond one application replica. The current in-memory stores cannot provide cross-replica correctness. Use database constraints and transactions for atomic idempotency claims and payment state updates.
- Provide a liveness probe to detect a stuck/unrecoverable process and a readiness probe to indicate whether this instance can accept requests (for example, startup complete and required local dependencies initialized). A temporary acquiring-bank outage should not automatically make every gateway replica unready; otherwise all replicas can be removed from service during a downstream incident. Report dependency health separately and use bounded timeouts/circuit protection rather than coupling liveness to the bank.
- Configure graceful shutdown: stop accepting new requests, allow in-flight HTTP and bank calls to finish within a bounded termination grace period, then exit. Ensure the orchestrator's termination grace period exceeds the application's request-drain window without permitting unbounded hangs.
- Set CPU and memory requests/limits from load-test measurements. Use a Horizontal Pod Autoscaler based on CPU and, when available, request/concurrency or latency signals. Ensure the database and bank can handle the scaled concurrency; scaling replicas alone can amplify downstream load.
- Use a rolling Deployment strategy with readiness-gated rollout, health/smoke verification, and a rollback path. A PodDisruptionBudget should preserve minimum serving capacity during voluntary disruptions, while not preventing planned maintenance indefinitely.
- Configure connection pooling for the HTTP client and database. Bound concurrent acquiring-bank requests and apply backpressure/bulkheads so a slow bank cannot consume all request threads or overwhelm the dependency. Keep payment submissions free from automatic retries unless bank-side idempotency makes them safe.

### Deployment pipeline

1. Build and run unit/integration tests, static checks, and dependency/security scans.
2. Build a versioned container image, scan it, and publish it to the approved registry.
3. Deploy the image to Kubernetes using Helm or reviewed manifests, with environment-specific configuration injected separately.
4. Verify rollout/readiness, then run smoke tests against the deployed endpoints and bank connectivity/degraded behavior.
5. If verification fails, stop promotion and roll back to the last known-good image/configuration; preserve diagnostics for investigation.

## Production-readiness roadmap

1. **Durable shared state:** replace the in-memory payment and idempotency maps with a relational database; define atomic claims, retention, migrations, backup, and recovery behavior. This is a prerequisite for safe horizontal scaling.
2. **Resolve ambiguous bank outcomes:** use acquiring-bank idempotency where supported; otherwise design reconciliation and an explicit unknown/pending outcome rather than assuming a timeout means failure. Do not add blind retries.
3. **Containerize and deploy:** create a gateway image and Kubernetes Deployment/Service/Ingress with probes, resource sizing, graceful shutdown, rollout/rollback, and disruption policy. Keep a single replica until state is shared and idempotency is safe across instances.
4. **Add observability and security controls:** instrument metrics, structured redacted logs, traces, alerts, authentication, TLS, and secret management; verify applicable payment-card data controls.
5. **Load test and tune:** measure end-to-end latency and throughput, then tune thread pools, HTTP/database connection pools, concurrency limits, resource requests, and HPA thresholds. Introduce asynchronous processing or additional infrastructure only if measurements and product requirements warrant it.

