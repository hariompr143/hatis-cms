package com.hatis.platform.integration.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbound webhook URL validation.
 *
 * <p>The platform fetches whatever address a tenant registers, so every case here is an
 * attempt to make it fetch something it should not. The interesting ones are not the
 * obvious {@code file://} URLs but the internal addresses: the cloud metadata endpoint,
 * the private ranges, and the cluster-local names that resolve inside Kubernetes.
 */
class WebhookUrlValidatorTest {

    @Test
    @DisplayName("an ordinary https endpoint is accepted")
    void acceptsHttps() {
        assertThat(WebhookUrlValidator.validate("https://hooks.acme.example/hatis").getHost())
                .isEqualTo("hooks.acme.example");
    }

    @Test
    @DisplayName("http is accepted, matching GitHub, because deliveries are signed")
    void acceptsHttp() {
        // Confidentiality is weaker over http, but integrity is not: every delivery
        // carries an HMAC. GitHub allows http endpoints too, so refusing them here would
        // only push tenants to proxy the traffic somewhere the platform cannot see.
        assertThat(WebhookUrlValidator.validate("http://hooks.acme.example/hatis").getScheme())
                .isEqualTo("http");
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://127.0.0.1:6379/_INFO",
            "jar:https://acme.example!/x",
            "dict://127.0.0.1:6379/INFO",
            "ftp://acme.example/x",
            "hooks.acme.example/hatis",
            "https://",
            "",
            "   "
    })
    @DisplayName("non-http schemes and hostless URLs are refused")
    void refusesNonHttpSchemes(String url) {
        assertThatThrownBy(() -> WebhookUrlValidator.validate(url))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @Test
    @DisplayName("credentials embedded in the URL are refused")
    void refusesUserInfo() {
        // A smuggling vector, and never needed: the platform authenticates by HMAC.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("https://user:pass@hooks.acme.example/x"))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
            "https://db.internal/x",
            "https://svc.cluster.local/x",
            "https://thing.local/x",
            "https://api.localhost/x",
            "https://localhost/x",
            "https://backend.svc/x",
            "https://host.lan/x",
            "https://host.intranet/x"
    })
    @DisplayName("internal and cluster-local hostnames are refused")
    void refusesInternalHostnames(String url) {
        assertThatThrownBy(() -> WebhookUrlValidator.validate(url))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://127.0.0.1:8080/x",
            "http://10.0.0.5/x",
            "http://10.1.2.3:5432/x",
            "http://192.168.1.1/x",
            "http://172.16.0.1/x",
            "http://172.31.255.254/x",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://100.64.0.1/x",
            "http://198.18.0.1/x",
            "http://[::1]/x",
            "http://0.0.0.0/x"
    })
    @DisplayName("internal address literals are refused without any DNS lookup")
    void refusesInternalAddresses(String url) {
        assertThatThrownBy(() -> WebhookUrlValidator.validate(url))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @Test
    @DisplayName("the cloud metadata endpoint is refused, which is the point of all this")
    void refusesTheMetadataEndpoint() {
        // 169.254.169.254 is how a tenant would try to read the platform's own cloud
        // credentials through it.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("link-local");
    }

    @Test
    @DisplayName("a public address literal is accepted")
    void acceptsPublicAddress() {
        assertThat(WebhookUrlValidator.validate("https://8.8.8.8/hatis").getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("validation itself performs no DNS lookup, so a hostile resolver cannot stall it")
    void validationDoesNotResolve() {
        // .invalid is reserved by RFC 6761 and never resolves. If validate() touched a
        // resolver this would fail or hang; it returns a URI instead.
        assertThat(WebhookUrlValidator.validate("https://never-resolves.invalid/hatis").getHost())
                .isEqualTo("never-resolves.invalid");
    }

    @Test
    @DisplayName("a name that resolves to loopback is refused at delivery time")
    void refusesNamesResolvingInternally() {
        // The case hostname checks cannot catch: a tenant's own DNS pointing a normal
        // looking name at the machine running the platform.
        assertThatThrownBy(() -> WebhookUrlValidator.assertDeliverable("https://localhost/hatis"))
                .isInstanceOf(PlatformExceptions.BusinessRuleViolation.class);
    }

    @Test
    @DisplayName("an unresolvable host is refused at delivery time")
    void refusesUnresolvableHost() {
        assertThatThrownBy(() -> WebhookUrlValidator.assertDeliverable("https://never-resolves.invalid/hatis"))
                .isInstanceOf(PlatformExceptions.BusinessRuleViolation.class);
    }

    @Test
    @DisplayName("an address literal that is public passes the delivery check without DNS")
    void deliversToPublicAddress() {
        // An IP literal needs no resolver, so this exercises the address checks directly.
        WebhookUrlValidator.assertDeliverable("https://8.8.8.8/hatis");
        assertThatThrownBy(() -> WebhookUrlValidator.assertDeliverable("http://169.254.169.254/x"))
                .isInstanceOf(PlatformExceptions.BusinessRuleViolation.class);
    }

    @Test
    @DisplayName("the refusal message does not describe the platform's network layout")
    void refusalDoesNotLeakTheAddress() {
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://10.1.2.3/x"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageNotContaining("10.1.2.3");
    }
}
