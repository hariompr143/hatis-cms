package com.hatis.platform.integration.adapter.http;

import com.hatis.platform.integration.domain.WebhookUrlValidator;
import com.hatis.platform.integration.port.out.WebhookTransport;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Posts signed events to customer URLs over Reactor Netty.
 *
 * <h2>Why the address is pinned</h2>
 *
 * This is the class that closes the DNS rebinding gap that
 * {@link WebhookUrlValidator} documents but cannot close on its own. The sequence is:
 *
 * <ol>
 *   <li>resolve the host exactly once, refusing every address that is not publicly
 *       routable ({@link WebhookUrlValidator#resolveDeliverable});</li>
 *   <li>connect to that vetted address by setting
 *       {@link HttpClient#remoteAddress(java.util.function.Supplier)}.</li>
 * </ol>
 *
 * The URL still carries the original hostname, so the {@code Host} header, the TLS SNI and
 * the certificate check all run against the name the customer registered — the connection
 * just does not ask DNS a second time. Vetting and connecting therefore act on the same
 * address, and a hostile authoritative server has no window in which to swap the answer.
 *
 * <p>Two implementation notes follow from that. A fresh client is built per delivery with
 * {@link ConnectionProvider#newConnection()}, because a pooled connection carries the pin
 * of whichever delivery opened it. And the JDK's own {@code HttpClient} could not be used
 * here at all: it refuses to let a caller set {@code Host}, so pinning by IP would have
 * meant either sending the wrong Host header or disabling certificate verification.
 *
 * <h2>What is and is not verified</h2>
 *
 * The address vetting is covered by {@code WebhookUrlValidatorTest}, and the pinning
 * itself by {@code WebhookTransportSocketTest}, which opens real sockets. It proves the
 * two properties that matter: a delivery the vetting refuses never reaches the wire, and
 * a delivery that passes reaches the vetted address and no other — the test's hostname is
 * {@code .invalid} and can never resolve, so a connection that arrived at all cannot have
 * gone through a second DNS lookup. What that test substitutes is only the vetting step,
 * approving a loopback address the real vetting would refuse so a local socket can stand
 * in for a customer's server; the refusal side runs against the real vetting.
 */
@Component
public class WebClientWebhookTransport implements WebhookTransport {

    private static final Logger log = LoggerFactory.getLogger(WebClientWebhookTransport.class);

    @Override
    public Outcome deliver(Request request) {
        URI uri;
        try {
            uri = new URI(request.url());
        } catch (URISyntaxException e) {
            return Outcome.unreachable("the stored webhook URL is not parseable");
        }

        List<InetAddress> vetted;
        try {
            vetted = resolveVetted(request.url());
        } catch (PlatformExceptions.BusinessRuleViolation e) {
            // Permanent: the same refusal will come back until the URL changes, so the
            // dispatcher records a skipped delivery instead of a retryable failure.
            log.warn("Refusing to deliver to {}: {}", uri.getHost(), e.getMessage());
            return Outcome.refused(e.getMessage());
        }

        InetAddress pinned = vetted.get(0);
        int port = portOf(uri);

        HttpClient client = HttpClient.create(ConnectionProvider.newConnection())
                .remoteAddress(() -> new InetSocketAddress(pinned, port))
                .responseTimeout(request.timeout());
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client))
                // A webhook body is a customer's event; a default 256 KB buffer would
                // truncate a large content payload into an undeliverable one.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();

        try {
            ResponseEntity<byte[]> response = webClient.post()
                    .uri(uri)
                    .headers(headers -> request.headers().forEach(headers::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.payload())
                    .retrieve()
                    .toEntity(byte[].class)
                    // A hard ceiling past the response timeout, so a stalled connection
                    // cannot hold a dispatcher thread indefinitely.
                    .block(request.timeout().plusSeconds(5));
            int status = response == null ? 0 : response.getStatusCode().value();
            if (status >= 200 && status < 300) {
                return Outcome.delivered(status);
            }
            return Outcome.rejected(status);
        } catch (WebClientResponseException e) {
            // The endpoint answered, it just did not accept the delivery.
            return Outcome.rejected(e.getStatusCode().value());
        } catch (RuntimeException e) {
            // Connect, TLS, read and timeout failures all land here. Only the exception
            // type is logged: the message can contain the URL, and the headers this
            // request carried include the signature.
            log.debug("Webhook delivery to {} failed: {}", uri.getHost(), e.getClass().getSimpleName());
            return Outcome.unreachable(e.getClass().getSimpleName());
        }
    }

    /**
     * Resolves the destination once and vets every address that lookup returned.
     *
     * <p>A seam rather than a direct call to {@link WebhookUrlValidator#resolveDeliverable}
     * for one reason only: {@code WebhookTransportSocketTest} has to point the delivery at
     * a socket it owns to observe where the connection lands, and the real vetting would
     * rightly refuse a local socket. Production never overrides this; the default below
     * <em>is</em> the production behaviour, and the tests that exercise the real vetting
     * call the real default through this same method.
     */
    protected List<InetAddress> resolveVetted(String url) {
        return WebhookUrlValidator.resolveDeliverable(url);
    }

    private static int portOf(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
