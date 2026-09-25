package com.hatis.platform.notification.adapter.channel;

import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.EmailTransport;
import com.hatis.platform.notification.port.out.RecipientDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The address boundary of email delivery.
 *
 * <p>An email address is the most identifying thing in a notification, so the sender resolves
 * one through the identity context's port and refuses to repeat it in a failure message: the
 * failure is logged, and a log is not a safe place for somebody's address. The tests below
 * assert that refusal as well as the happy path, because it is the half that a later change
 * would quietly remove.
 */
@DisplayName("Email channel sender")
class EmailChannelSenderTest {

    private final EmailTransport transport = mock(EmailTransport.class);
    private final RecipientDirectory recipients = mock(RecipientDirectory.class);

    @Test
    @DisplayName("the address on file is handed to the transport with the message")
    void aKnownAddressIsHandedToTheTransport() {
        Notification notification = notification();
        when(recipients.emailOf(notification.getUserId())).thenReturn(Optional.of("editor@example.com"));

        new EmailChannelSender(transport, recipients).send(notification);

        var sent = org.mockito.ArgumentCaptor.forClass(EmailTransport.Email.class);
        verify(transport).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("editor@example.com");
        assertThat(sent.getValue().subject()).isEqualTo(notification.getSubject());
        assertThat(sent.getValue().body()).isEqualTo(notification.getBody());
    }

    @Test
    @DisplayName("no address fails without repeating the identity in the message")
    void aMissingAddressFailsWithoutEchoingIt() {
        Notification notification = notification();
        when(recipients.emailOf(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new EmailChannelSender(transport, recipients).send(notification))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No address is on file")
                .hasMessageNotContaining(notification.getUserId().toString());

        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("the sender claims the EMAIL channel and nothing else")
    void theSenderClaimsTheEmailChannel() {
        assertThat(new EmailChannelSender(transport, recipients).channel())
                .isEqualTo(Notification.Channel.EMAIL);
    }

    private static Notification notification() {
        return Notification.queue(UUID.randomUUID(), UUID.randomUUID(), Notification.Channel.EMAIL,
                "billing.invoice.issued", "Your invoice is ready", "Invoice 2026-09 is available.");
    }
}
