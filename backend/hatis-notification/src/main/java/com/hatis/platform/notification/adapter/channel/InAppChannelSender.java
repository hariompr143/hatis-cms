package com.hatis.platform.notification.adapter.channel;

import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.notification.port.out.ChannelSender;
import org.springframework.stereotype.Component;

/**
 * In-app delivery: the notification row <em>is</em> the delivery.
 *
 * <p>This is not a stub that reports success without doing anything. An {@code IN_APP}
 * notification reaches its recipient by existing in {@code ntf_notifications}, which is what
 * the console reads; writing the row and marking it sent is the whole mechanism, and there is
 * no second system that could disagree. What would be a stub is a sender that claimed success
 * for a channel that needed a network call it never made.
 */
@Component
public class InAppChannelSender implements ChannelSender {

    @Override
    public Notification.Channel channel() {
        return Notification.Channel.IN_APP;
    }

    @Override
    public void send(Notification notification) {
        // Nothing to do beyond the row the service already wrote and is about to mark sent.
    }
}
