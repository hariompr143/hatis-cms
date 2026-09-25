package com.hatis.platform.notification.port.out;

import com.hatis.platform.notification.domain.Notification;

/**
 * Delivers a notification over one channel.
 *
 * <p>One implementation per {@link Notification.Channel}. A channel with no implementation
 * available at this deployment is not substituted with a do-nothing sender: the service
 * records the notification as failed and says so, because a console that reports "sent" for
 * a message nobody can receive is worse than one that reports a failure.
 *
 * <p>{@link #send} returns normally when the channel accepted the message and throws when it
 * did not. The only exception a caller may rely on is that a failed send does not corrupt the
 * notification row: the service marks it failed and keeps the attempt count.
 */
public interface ChannelSender {

    Notification.Channel channel();

    /**
     * Attempts delivery.
     *
     * @throws RuntimeException when the channel refused or could not be reached. The service
     *                          records the failure; it does not propagate it, because a
     *                          notification is never worth failing the operation that raised it.
     */
    void send(Notification notification);
}
