package dev.notch.api.outreach;

import java.time.Instant;
import java.util.UUID;

/** JSON shapes for outreach. */
public final class OutreachView {

    private OutreachView() {
    }

    /**
     * @param delivery    IN_APP, EMAILED or EMAIL_FAILED
     * @param mailEnabled whether Notch can send email at all (so the UI can say why it stayed in the app)
     */
    public record Sent(UUID id, UUID candidateId, String handle, String delivery, boolean mailEnabled) {
    }

    /** A recruiter's view of one message: who, and how they answered (SENT, INTERESTED or DECLINED). */
    public record Status(UUID id, UUID candidateId, String handle, String role, String status, String delivery,
                         Instant sentAt, Instant respondedAt) {
    }

    /** A candidate's view of one message. */
    public record Message(UUID id, String drive, String role, String recruiter, String subject, String body,
                          String status, Instant sentAt, Instant respondedAt) {
    }
}
