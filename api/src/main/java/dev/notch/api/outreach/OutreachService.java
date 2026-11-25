package dev.notch.api.outreach;

import dev.notch.api.config.ApiException;
import dev.notch.api.config.NotchProperties;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.llm.OutreachPrompt;
import dev.notch.api.portfolio.CandidatePool;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.api.recruit.RecruitRepository;
import dev.notch.api.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A recruiter contacts a candidate from a drive: Notch drafts the message from the candidate's
 * evidence (with the language model when there is one, from a template otherwise), delivers it to
 * the candidate's Notch inbox, and emails it when mail is set up and the candidate signed in with
 * an email address. The candidate answers interested or not, and the recruiter sees the answer.
 */
@Service
public class OutreachService {

    private static final System.Logger LOG = System.getLogger(OutreachService.class.getName());

    public record Draft(String subject, String body, boolean written) {
    }

    private final OutreachRepository repo;
    private final RecruitRepository drives;
    private final CandidatePool pool;
    private final LlmClient llm;
    private final Mailer mailer;
    private final CurrentUser currentUser;
    private final String webUrl;

    public OutreachService(OutreachRepository repo, RecruitRepository drives, CandidatePool pool, LlmClient llm,
                           Mailer mailer, CurrentUser currentUser, NotchProperties props) {
        this.repo = repo;
        this.drives = drives;
        this.pool = pool;
        this.llm = llm;
        this.mailer = mailer;
        this.currentUser = currentUser;
        List<String> origins = props.cors().allowedOrigins();
        this.webUrl = origins == null || origins.isEmpty() ? "" : origins.get(0).replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ recruiter side

    public Draft draft(UUID driveId, UUID roleId, UUID candidateId) {
        Target t = target(driveId, roleId, candidateId);
        OutreachPrompt prompt = prompt(t);
        for (int attempt = 0; attempt < 2; attempt++) { // one retry: a model reply is occasionally not clean JSON
            try {
                Map<String, Object> reply = llm.outreachEmail(prompt);
                if (reply == null) break; // no model configured
                String subject = text(reply.get("subject"), 120);
                String body = text(reply.get("body"), 4000);
                if (!subject.isEmpty() && !body.isEmpty()) return new Draft(subject, body, true);
            } catch (IllegalStateException e) {
                LOG.log(System.Logger.Level.INFO, "Outreach draft attempt {0} failed: {1}", attempt + 1, e.getMessage());
            }
        }
        return template(prompt);
    }

    public OutreachView.Sent send(UUID driveId, UUID roleId, UUID candidateId, String subject, String body) {
        Target t = target(driveId, roleId, candidateId);
        if (repo.exists(driveId, candidateId)) {
            throw ApiException.conflict("already_contacted", "You've already contacted @" + t.candidate().handle() + " from this drive.");
        }
        UUID id = UUID.randomUUID();
        repo.insert(id, driveId, roleId, t.roleName(), currentUser.id(), candidateId, subject.strip(), body.strip(), "IN_APP");

        String delivery = "IN_APP";
        OutreachRepository.Contact to = repo.contact(candidateId).orElse(null);
        if (mailer.configured() && to != null && to.email() != null && !to.email().isBlank()) {
            OutreachRepository.Contact from = repo.contact(currentUser.id()).orElse(null);
            String footer = "\n\n—\nRead and answer this on Notch: " + webUrl + "/start"
                    + "\nYou're receiving this because your Notch profile is public.";
            boolean ok = mailer.send(to.email(), subject.strip(), body.strip() + footer,
                    from == null ? null : from.email(), from == null ? null : from.handle());
            delivery = ok ? "EMAILED" : "EMAIL_FAILED";
            repo.setDelivery(id, delivery);
        }
        return new OutreachView.Sent(id, candidateId, t.candidate().handle(), delivery, mailer.configured());
    }

    public List<OutreachView.Status> forDrive(UUID driveId) {
        requireDrive(driveId);
        return repo.forDrive(driveId).stream()
                .map(r -> new OutreachView.Status(r.id(), r.candidateId(), r.candidateHandle(), r.roleName(), r.status(),
                        r.delivery(), r.createdAt(), r.respondedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ candidate side

    public List<OutreachView.Message> inbox() {
        return repo.forCandidate(currentUser.id()).stream().map(OutreachService::message).toList();
    }

    public OutreachView.Message respond(UUID id, boolean interested) {
        if (!repo.respond(id, currentUser.id(), interested ? "INTERESTED" : "DECLINED")) {
            throw ApiException.notFound("That message");
        }
        return repo.get(id).map(OutreachService::message).orElseThrow(() -> ApiException.notFound("That message"));
    }

    // ------------------------------------------------------------------ helpers

    private record Target(RecruitRepository.Drive drive, String roleName, CandidatePool.Member candidate,
                          List<String> strengths) {
    }

    private Target target(UUID driveId, UUID roleId, UUID candidateId) {
        RecruitRepository.Drive drive = requireDrive(driveId);
        if (candidateId.equals(currentUser.id())) {
            throw ApiException.badRequest("self_contact", "You can't contact yourself.");
        }
        RecruitRepository.Role role = roleId == null ? null
                : drives.roles(driveId).stream().filter(r -> r.id().equals(roleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That role"));
        CandidatePool.Member candidate = pool.snapshot().members().stream()
                .filter(m -> m.userId().equals(candidateId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That candidate"));
        return new Target(drive, role == null ? drive.title() : role.name(), candidate, strengths(candidate, role));
    }

    private RecruitRepository.Drive requireDrive(UUID driveId) {
        return drives.drive(currentUser.id(), driveId).orElseThrow(() -> ApiException.notFound("That drive"));
    }

    /** The candidate's evidence on the role's skills first, then their other strongest skills, at most four lines. */
    static List<String> strengths(CandidatePool.Member m, RecruitRepository.Role role) {
        Set<Integer> wanted = new HashSet<>();
        if (role != null) for (RecruitRepository.Requirement q : role.requirements()) wanted.add(q.skillId());
        List<Standing> sorted = new ArrayList<>(m.skills());
        sorted.sort(Comparator.<Standing>comparingInt(s -> wanted.contains(s.skill().id()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(Standing::score).reversed()));
        List<String> out = new ArrayList<>();
        for (Standing s : sorted) {
            if (out.size() == 4) break;
            out.add(s.skill().name() + " " + Math.round(s.score()) + ", " + s.level().name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private OutreachPrompt prompt(Target t) {
        String recruiter = repo.contact(currentUser.id()).map(OutreachRepository.Contact::handle).orElse("The hiring team");
        return new OutreachPrompt(recruiter, t.drive().title(), t.roleName(), t.candidate().handle(), t.candidate().headline(),
                t.strengths());
    }

    /** The message without a model: plain, specific and short. */
    static Draft template(OutreachPrompt p) {
        // "SQL 70, defended" reads as "defended SQL": evidence in words, no raw scores.
        List<String> top = p.strengths().stream().limit(2)
                .map(s -> s.replaceFirst("^(.+?) \\d+, (\\w+)$", "$2 $1")).toList();
        String evidence = top.isEmpty() ? "the work on your Notch profile" : "your " + String.join(" and ", top) + " work";
        String body = "Hi @" + p.candidateHandle() + ",\n\n"
                + (p.roleName().equalsIgnoreCase(p.driveTitle())
                        ? "I'm hiring for " + p.driveTitle() + ", and " + evidence
                        : "I'm hiring for the " + p.roleName() + " role in our " + p.driveTitle() + " drive, and " + evidence)
                + " stood out. It's backed by your own code rather than a list of claims, which is exactly what we look for.\n\n"
                + "Would you be open to a short conversation about the role? If you're interested, let me know on Notch and I'll share the next steps.\n\n"
                + "Best,\n" + p.recruiterName();
        String subject = p.roleName().equalsIgnoreCase(p.driveTitle()) ? p.driveTitle() + ": would you like to talk?"
                : p.roleName() + " role: would you like to talk?";
        return new Draft(subject, body, false);
    }

    private static OutreachView.Message message(OutreachRepository.Row r) {
        return new OutreachView.Message(r.id(), r.driveTitle(), r.roleName(), r.recruiterHandle(), r.subject(), r.body(),
                r.status(), r.createdAt(), r.respondedAt());
    }

    private static String text(Object o, int max) {
        if (o == null) return "";
        String s = o.toString().strip();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
