package dev.notch.api.outreach;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Sends plain-text email through an SMTP account, by default Gmail with an app password
 * (Google Account, Security, App passwords). Off unless {@code NOTCH_MAIL_USER} and
 * {@code NOTCH_MAIL_PASSWORD} are set; outreach then stays in the app.
 */
@Component
public class Mailer {

    private static final System.Logger LOG = System.getLogger(Mailer.class.getName());

    private final String user;
    private final String password;
    private final String fromName;
    private final String host;
    private final int port;

    public Mailer(@Value("${NOTCH_MAIL_USER:}") String user, @Value("${NOTCH_MAIL_PASSWORD:}") String password,
                  @Value("${NOTCH_MAIL_FROM_NAME:Notch}") String fromName,
                  @Value("${NOTCH_MAIL_HOST:smtp.gmail.com}") String host, @Value("${NOTCH_MAIL_PORT:587}") int port) {
        this.user = user == null ? "" : user.strip();
        // Google shows app passwords in groups of four; the spaces are not part of it.
        this.password = password == null ? "" : password.replace(" ", "").strip();
        this.fromName = fromName;
        this.host = host;
        this.port = port;
    }

    public boolean configured() {
        return !user.isEmpty() && !password.isEmpty();
    }

    /**
     * Sends one message. Returns false (and logs why) when mail is off or the server refuses;
     * never throws, so a mail problem can't fail the request that triggered it.
     *
     * @param replyTo where the candidate's reply should go (the recruiter), or null
     */
    public boolean send(String to, String subject, String body, String replyTo, String senderName) {
        if (!configured() || to == null || to.isBlank()) return false;
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "8000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        Session session = Session.getInstance(p, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, password);
            }
        });
        try {
            MimeMessage m = new MimeMessage(session);
            String name = senderName == null || senderName.isBlank() ? fromName : senderName + " via " + fromName;
            m.setFrom(new InternetAddress(user, name, StandardCharsets.UTF_8.name()));
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
            if (replyTo != null && !replyTo.isBlank()) m.setReplyTo(InternetAddress.parse(replyTo, true));
            m.setSubject(subject, StandardCharsets.UTF_8.name());
            m.setText(body, StandardCharsets.UTF_8.name());
            Transport.send(m);
            return true;
        } catch (MessagingException | UnsupportedEncodingException e) {
            LOG.log(System.Logger.Level.WARNING, "Outreach email to a candidate was not sent: {0}", e.getMessage());
            return false;
        }
    }
}
