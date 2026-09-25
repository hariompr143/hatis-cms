package com.hatis.platform.notification.adapter.channel;

import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.ChannelSender;
import com.hatis.platform.notification.port.out.EmailTransport;
import com.hatis.platform.notification.port.out.RecipientDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Email delivery, present only where a transport is configured.
 *
 * <p>The conditional is the design, not a convenience. {@code hatis.notification.email.enabled}
 * decides whether an {@code EMAIL} sender exists at all; where it is false, the channel is
 * absent rather than fake, and a request for it produces a notification recorded as failed
 * with a log line naming the missing sender. A sender that swallowed mail into a log would
 * make the console report a delivery that never happened.
 */
@Component
@ConditionalOnProperty(prefix = "hatis.notification.email", name = "enabled", havingValue = "true")
public class EmailChannelSender implements ChannelSender {

    private final EmailTransport transport;
    private final RecipientDirectory recipients;

    public EmailChannelSender(EmailTransport transport, RecipientDirectory recipients) {
        this.transport = transport;
        this.recipients = recipients;
    }

    @Override
    public Notification.Channel channel() {
        return Notification.Channel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        String address = recipients.emailOf(notification.getUserId())
                // The message is not repeated here: the user id is the useful part, and an
                // address in an exception message ends up in a log.
                .orElseThrow(() -> new IllegalStateException(
                        "No address is on file for the notification recipient"));
        transport.send(new EmailTransport.Email(address, notification.getSubject(), notification.getBody()));
    }
}
