package com.hatis.platform.integration.adapter.http;

import com.hatis.platform.integration.port.out.WebhookTransport;
import com.hatis.platform.integration.port.out.WebhookTransport.Outcome;
import com.hatis.platform.integration.port.out.WebhookTransport.Request;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The outbound webhook pinning, observed from the far end of a real socket.
 *
 * <p>The class under test documents that it resolves once, vets every returned address
 * and then connects to the vetted address rather than asking DNS again. That is a
 * connection-level property: no unit test can see it, because what needs asserting is
 * where a TCP connection lands. So the tests here open real sockets and either assert
 * that nothing ever connects to them, or that the request arrived with the right bytes
 * on the right listener.
 *
 * <h2>What is real and what is substituted</h2>
 *
 * Everything about the connection is production code and real networking. One step is
 * substituted: the address vetting approves a loopback address so that a local socket can
 * stand in for a customer's server — the real vetting would rightly refuse loopback, and
 * the tests below pin that refusal against real sockets through the real vetting. The pin
 * test's hostname is {@code .invalid}, which RFC 6761 reserves to never resolve, so a
 * connection that arrived at all cannot have gone through a second DNS lookup: the
 * request is exactly what pinning by address produces, and nothing else produces it.
 */
class WebhookTransportSocketTest {

    private static final byte[] PAYLOAD = "{\"event\":\"content.published\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a delivery to a loopback address is refused before anything connects")
    void aLoopbackTargetIsRefusedBeforeItIsContacted() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String url = "http://127.0.0.1:" + listener.getLocalPort() + "/hook";

            Outcome outcome = new WebClientWebhookTransport().deliver(request(url));

            assertThat(outcome.result()).isEqualTo(Outcome.Result.REFUSED_BY_POLICY);
            assertThat(outcome.isRetryable()).isFalse();
            assertNothingConnected(listener);
        }
    }

    @Test
    @DisplayName("a name that resolves to loopback is refused before anything connects")
    void aNameResolvingToLoopbackIsRefusedBeforeItIsContacted() throws Exception {
        // The case a literal-address check cannot cover: ordinary-looking names whose
        // DNS points at the machine running the platform. localhost is that case on
        // every machine, so the real resolver is exercised here, not a stub.
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String url = "http://localhost:" + listener.getLocalPort() + "/hook";

            Outcome outcome = new WebClientWebhookTransport().deliver(request(url));

            assertThat(outcome.result()).isEqualTo(Outcome.Result.REFUSED_BY_POLICY);
            assertNothingConnected(listener);
        }
    }

    @Test
    @DisplayName("a delivery to the cloud metadata endpoint is refused and echoes no address")
    void theMetadataEndpointIsRefusedWithoutEchoingIt() {
        Outcome outcome = new WebClientWebhookTransport()
                .deliver(request("http://169.254.169.254/latest/meta-data/"));

        assertThat(outcome.result())
                .overridingErrorMessage("delivery outcome: %s (status %d, detail: %s)",
                        outcome.result(), outcome.httpStatus(), outcome.detail())
                .isEqualTo(Outcome.Result.REFUSED_BY_POLICY);
        // The refusal is a customer-visible delivery record, and the address describes
        // the platform's network, not theirs.
        assertThat(outcome.detail()).doesNotContain("169.254");
    }

    @Test
    @DisplayName("the connection reaches the vetted address even though the hostname can never resolve")
    void theConnectionReachesTheVettedAddressAndNoOther() throws Exception {
        AtomicReference<Captured> seen = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            seen.set(new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestBody().readAllBytes()));
            arrived.countDown();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes(StandardCharsets.US_ASCII));
            exchange.close();
        });
        server.start();
        try {
            InetAddress vetted = InetAddress.getLoopbackAddress();
            int port = server.getAddress().getPort();
            // RFC 6761 reserves .invalid: it has no address, ever. If this class were to
            // resolve the URL again to connect, the delivery could not arrive at all.
            String url = "http://pin-test.invalid:" + port + "/hook";
            WebClientWebhookTransport transport = new WebClientWebhookTransport() {
                @Override
                protected List<InetAddress> resolveVetted(String raw) {
                    return List.of(vetted);
                }
            };

            Outcome outcome = transport.deliver(request(url));

            assertThat(outcome.result())
                    .overridingErrorMessage("delivery outcome: %s (status %d, detail: %s)",
                            outcome.result(), outcome.httpStatus(), outcome.detail())
                    .isEqualTo(Outcome.Result.DELIVERED);
            assertThat(arrived.await(5, TimeUnit.SECONDS))
                    .overridingErrorMessage("no request reached the vetted socket")
                    .isTrue();
            Captured captured = seen.get();
            assertThat(captured.method())
                    .overridingErrorMessage("request method was %s", captured.method())
                    .isEqualTo("POST");
            assertThat(captured.path())
                    .overridingErrorMessage("request target was %s", captured.path())
                    .isEqualTo("/hook");
            // The hostname survived into the request: TLS SNI and certificate checks
            // run against the registered name while the bytes go to the vetted address.
            assertThat(captured.hostHeader() == null ? "" : captured.hostHeader().toLowerCase(Locale.ROOT))
                    .overridingErrorMessage("Host header was %s", captured.hostHeader())
                    .contains("pin-test.invalid:" + port);
            assertThat(new String(captured.body(), StandardCharsets.UTF_8))
                    .overridingErrorMessage("request body was %s",
                            new String(captured.body(), StandardCharsets.UTF_8))
                    .isEqualTo(new String(PAYLOAD, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("without the seam the same URL is refused, because the hostname does not resolve")
    void theUnmodifiedTransportCannotReachTheSocketAtAll() throws Exception {
        // The control for the pin test: the only way that request could reach the
        // listener is the pinned address, because the name itself leads nowhere.
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String url = "http://pin-test.invalid:" + listener.getLocalPort() + "/hook";

            Outcome outcome = new WebClientWebhookTransport().deliver(request(url));

            assertThat(outcome.result())
                    .overridingErrorMessage("delivery outcome: %s (status %d, detail: %s)",
                            outcome.result(), outcome.httpStatus(), outcome.detail())
                    .isEqualTo(Outcome.Result.REFUSED_BY_POLICY);
            assertNothingConnected(listener);
        }
    }

    private static Request request(String url) {
        return new Request(url, PAYLOAD,
                Map.of("X-Hatis-Signature", "test-signature"), Duration.ofSeconds(5));
    }

    /**
     * Asserts the listener saw no connection at all. The refusal happens before any
     * socket is opened, so a connection here means the delivery reached the wire.
     */
    private static void assertNothingConnected(ServerSocket listener) {
        try {
            listener.setSoTimeout(250);
            try (Socket unexpected = listener.accept()) {
                fail("a connection arrived on port " + listener.getLocalPort()
                        + ", but the delivery should never have reached the wire");
            }
        } catch (SocketTimeoutException expected) {
            // Nothing connected.
        } catch (IOException e) {
            throw new AssertionError("checking for an unexpected connection failed", e);
        }
    }

    private record Captured(String method, String path, String hostHeader, byte[] body) {
    }
}
