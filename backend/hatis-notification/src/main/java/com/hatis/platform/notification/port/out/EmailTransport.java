package com.hatis.platform.notification.port.out;

/**
 * Outbound port to a mail transfer agent.
 *
 * <p>Separate from {@link ChannelSender} because the two answer different questions.
 * {@code ChannelSender} is "how does an {@code EMAIL} notification leave the platform",
 * which includes the choice to have no transport at all; this port is "hand these bytes to
 * an SMTP server", which an installation either has configured or does not. Keeping them
 * apart is what lets the email channel be absent on a deployment without email while the
 * channel itself remains a first-class concept.
 */
public interface EmailTransport {

    /**
     * Sends one message.
     *
     * @throws RuntimeException when the message was not accepted. Implementations must not
     *                          include the recipient's address or the server's response in
     *                          the exception message: it is logged, and a log is not a
     *                          safe place for an email address.
     */
    void send(Email email);

    record Email(String to, String subject, String body) {

        public Email {
            if (to == null || to.isBlank()) {
                throw new IllegalArgumentException("An email needs a recipient");
            }
            if (subject == null) {
                throw new IllegalArgumentException("An email needs a subject");
            }
            if (body == null) {
                throw new IllegalArgumentException("An email needs a body");
            }
        }
    }
}
