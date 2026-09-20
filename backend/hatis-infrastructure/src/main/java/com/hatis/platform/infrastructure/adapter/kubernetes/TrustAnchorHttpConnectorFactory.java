package com.hatis.platform.infrastructure.adapter.kubernetes;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.nio.file.Path;

/**
 * Builds a reactor-netty connector that trusts the in-cluster CA.
 *
 * <p>Isolated so that the trust configuration is in exactly one place and can be
 * reviewed as a single unit. There is no code path here that produces a
 * trust-everything context.
 */
public final class TrustAnchorHttpConnectorFactory {

    private TrustAnchorHttpConnectorFactory() {
    }

    public static ReactorClientHttpConnector create(Path caCertificate) {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(caCertificate.toFile())
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .secure(spec -> spec.sslContext(sslContext));
            return new ReactorClientHttpConnector(httpClient);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to configure TLS trust for the Kubernetes API server", e);
        }
    }
}
