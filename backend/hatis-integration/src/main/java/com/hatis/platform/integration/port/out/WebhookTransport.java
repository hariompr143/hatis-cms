package com.hatis.platform.integration.port.out;

import java.time.Duration;
import java.util.Map;

/**
 * The seam through which the platform posts a signed event to a customer URL.
 *
 * <p>It exists as a port because the part that is hard to get right is not HTTP, it is
 * <em>which address the request may reach</em>. The dispatcher owns signing, retry
 * scheduling and the delivery record; the implementation owns DNS, TLS, timeouts and
 * address pinning. Splitting them means the delivery logic can be tested without a
 * network, and the SSRF defence can be reviewed in one place.
 *
 * <h2>The contract an implementation must honour</h2>
 *
 * <ol>
 *   <li>Resolve the host itself, refuse every address that is not publicly routable, and
 *       then connect <em>to the address it vetted</em> rather than resolving again. Two
 *       lookups is the whole of a DNS rebinding attack; pinning closes it.
 *       {@link com.hatis.platform.integration.domain.WebhookUrlValidator#assertDeliverable}
 *       is the vetting half, and it is not sufficient on its own.</li>
 *   <li>Never throw for an ordinary delivery failure. A refused connection, a timeout and
 *       a 500 are all results, expressed as {@link Outcome}, because the caller has to
 *       record them and decide whether to retry. Throwing would turn a customer's
 *       misconfigured endpoint into a platform error.</li>
 *   <li>Never log or return the request headers. One of them is the HMAC signature, and
 *       the payload is customer content.</li>
 * </ol>
 */
public interface WebhookTransport {

    /**
     * Posts the payload and reports what happened.
     *
     * @return the outcome; never null, and never a thrown exception for a delivery failure
     */
    Outcome deliver(Request request);

    /**
     * One outbound POST.
     *
     * @param url     the endpoint URL, already validated at registration
     * @param payload the exact bytes to send, which is also what the signature covers
     * @param headers request headers, including the signature and delivery identifiers
     */
    record Request(String url, byte[] payload, Map<String, String> headers, Duration timeout) {

        public Request {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url is required for a webhook delivery");
            }
            if (payload == null) {
                throw new IllegalArgumentException("payload is required for a webhook delivery");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /**
     * What happened to one attempt.
     *
     * <p>The distinction between {@link Result#REJECTED} and {@link Result#UNREACHABLE}
     * matters more than it looks: a 4xx means the customer's endpoint answered and does
     * not want this delivery, so retrying is noise, whereas a timeout means the delivery
     * may still be wanted and should be retried.
     */
    record Outcome(Result result, int httpStatus, String detail) {

        public enum Result {
            /** The endpoint accepted the delivery with a 2xx. */
            DELIVERED(true),
            /** The endpoint answered with a non-2xx status. */
            REJECTED(true),
            /** No usable response: DNS, connect, TLS or read failure. */
            UNREACHABLE(true),
            /**
             * The platform refused to send, because the URL no longer resolves to a
             * publicly routable address. Not retryable: the same answer will come back
             * until the customer changes the URL.
             */
            REFUSED_BY_POLICY(false);

            private final boolean retryable;

            Result(boolean retryable) {
                this.retryable = retryable;
            }

            /** Whether another attempt could plausibly succeed. */
            public boolean isRetryable() {
                return retryable;
            }
        }

        public static Outcome delivered(int httpStatus) {
            return new Outcome(Result.DELIVERED, httpStatus, null);
        }

        public static Outcome rejected(int httpStatus) {
            return new Outcome(Result.REJECTED, httpStatus, null);
        }

        public static Outcome unreachable(String detail) {
            return new Outcome(Result.UNREACHABLE, 0, detail);
        }

        public static Outcome refused(String detail) {
            return new Outcome(Result.REFUSED_BY_POLICY, 0, detail);
        }

        public boolean isDelivered() {
            return result == Result.DELIVERED;
        }

        public boolean isRetryable() {
            return result.isRetryable();
        }
    }
}
