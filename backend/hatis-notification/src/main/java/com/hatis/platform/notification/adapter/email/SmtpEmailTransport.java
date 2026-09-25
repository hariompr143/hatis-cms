package com.hatis.platform.notification.adapter.email;

import com.hatis.platform.notification.port.out.EmailTransport;
import com.hatis.platform.shared.config.PlatformProperties;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SMTP delivery.
 *
 * <h2>The credential never lives in configuration</h2>
 *
 * The host, port and sender address are ordinary configuration; the password is not. It is
 * read from the {@link SecretStore} at the path named by
 * {@code hatis.notification.email.password-secret} for each message it sends, and the
 * {@link Secret} is destroyed as soon as the transport has handed it over. Nothing about the
 * password is logged, returned or held in a field, so it is not in a heap dump of a bean and
 * not in a configuration audit trail.
 *
 * <p>Reading it per message rather than at start-up is the deliberate part: a secret store
 * that is briefly unavailable must not stop the platform booting, and it must fail the
 * <em>delivery</em> rather than the deployment. The notification records the failure and the
 * attempt, which is the same outcome a rotated credential produces.
 *
 * <h2>No connection pooling</h2>
 *
 * A sender is built per message. JavaMail's transport is not thread-safe and this adapter is
 * called from request threads, so sharing one instance would trade a connection per message
 * for a class of intermittent corruption. A mail server that needs a persistent connection to
 * stay affordable is a load problem for Phase 2, not a reason to accept that trade now.
 */
@Component
@ConditionalOnProperty(prefix = "hatis.notification.email", name = "enabled", havingValue = "true")
public class SmtpEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailTransport.class);

    private final PlatformProperties.Notification.Email settings;
    private final SecretStore secretStore;

    public SmtpEmailTransport(PlatformProperties properties, SecretStore secretStore) {
        this.settings = properties.notification().email();
        this.secretStore = secretStore;
    }

    @Override
    public void send(Email email) {
        JavaMailSenderImpl sender = mailSender();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.getFrom());
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());

        try {
            sender.send(message);
        } catch (MailException e) {
            // The mail library's message quotes the recipient and the server's response, and
            // this one is both logged and stored on the notification. The class name is enough
            // to tell "connection refused" from "authentication failed" during an incident.
            log.warn("SMTP delivery failed with {}: {}", e.getClass().getName(),
                    e.getMostSpecificCause().getClass().getName());
            throw new PlatformExceptions.DependencyUnavailable("The SMTP server", "the message was rejected");
        }
    }

    private JavaMailSenderImpl mailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setDefaultEncoding("UTF-8");

        boolean authenticating = settings.getUsername() != null && !settings.getUsername().isBlank();
        if (authenticating) {
            sender.setUsername(settings.getUsername());
            sender.setPassword(password());
        }
        String timeout = String.valueOf(settings.getTimeout().toMillis());
        sender.getJavaMailProperties().putAll(Map.of(
                "mail.smtp.auth", String.valueOf(authenticating),
                "mail.smtp.starttls.enable", String.valueOf(settings.isStartTls()),
                "mail.smtp.connectiontimeout", timeout,
                "mail.smtp.timeout", timeout,
                "mail.smtp.writetimeout", timeout));
        return sender;
    }

    private String password() {
        String path = settings.getPasswordSecret();
        if (path == null || path.isBlank()) {
            return "";
        }
        try (Secret secret = secretStore.get(path)) {
            return secret.reveal();
        } catch (RuntimeException e) {
            // Not unwrapped or forwarded: a secret-store failure can name the path it failed on.
            throw new PlatformExceptions.DependencyUnavailable("The secret store",
                    "the SMTP credential could not be read");
        }
    }
}
