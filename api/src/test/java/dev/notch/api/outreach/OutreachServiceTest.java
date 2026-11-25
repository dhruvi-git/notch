package dev.notch.api.outreach;

import dev.notch.api.llm.OutreachPrompt;
import dev.notch.api.portfolio.CandidatePool;
import dev.notch.api.portfolio.core.SkillCatalog.Skill;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.api.recruit.RecruitRepository;
import dev.notch.engine.score.SkillScoring.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutreachServiceTest {

    private static Standing s(int id, String name, double score, Level level) {
        return new Standing(new Skill(id, name, "Data"), score, level, List.of());
    }

    @Test
    void strengthsPutTheRolesSkillsFirstAndKeepFour() {
        CandidatePool.Member m = new CandidatePool.Member(UUID.randomUUID(), "ria", "Data analyst", "Data", List.of(
                s(1, "Python", 90, Level.VERIFIED), s(2, "React", 95, Level.VERIFIED), s(17, "SQL", 70, Level.DEFENDED),
                s(3, "Docker", 60, Level.SHOWN), s(4, "Go", 50, Level.SHOWN)));
        RecruitRepository.Role role = new RecruitRepository.Role(UUID.randomUUID(), "Data analyst", 2, 50,
                List.of(new RecruitRepository.Requirement(17, 3, true, "VERIFIED"), new RecruitRepository.Requirement(1, 2, false, "SHOWN")));

        List<String> out = OutreachService.strengths(m, role);
        assertEquals(List.of("Python 90, verified", "SQL 70, defended", "React 95, verified", "Docker 60, shown"), out);
        assertEquals(4, OutreachService.strengths(m, null).size());
        assertEquals("React 95, verified", OutreachService.strengths(m, null).get(0));
    }

    @Test
    void templateNamesTheCandidateRoleAndEvidenceWithNoPlaceholders() {
        OutreachPrompt p = new OutreachPrompt("dhruvi", "Campus drive", "Data analyst", "ria", "Data analyst",
                List.of("SQL 70, defended", "Python 90, verified", "React 95, verified"));
        OutreachService.Draft d = OutreachService.template(p);
        assertFalse(d.written());
        assertTrue(d.subject().contains("Data analyst"));
        assertTrue(d.body().startsWith("Hi @ria,"));
        assertTrue(d.body().contains("your defended SQL and verified Python work stood out"), d.body());
        assertFalse(d.body().matches("(?s).*\\d.*"), "no raw scores: " + d.body());
        assertTrue(d.body().endsWith("Best,\ndhruvi"));
        assertFalse(d.body().matches("(?s).*[\\[{<].*"), "no template placeholders left: " + d.body());
    }

    @Test
    void templateDoesNotRepeatARoleNamedAfterTheDrive() {
        OutreachService.Draft d = OutreachService.template(new OutreachPrompt("dhruvi", "Campus drive", "Campus drive", "ria", null,
                List.of("SQL 70, verified")));
        assertTrue(d.body().contains("I'm hiring for Campus drive, and your verified SQL work stood out"), d.body());
        assertEquals("Campus drive: would you like to talk?", d.subject());
    }

    @Test
    void templateWithoutEvidenceStillReads() {
        OutreachService.Draft d = OutreachService.template(new OutreachPrompt("Hiring team", "Drive", "Analyst", "sam", null, List.of()));
        assertTrue(d.body().contains("the work on your Notch profile"));
    }

    @Test
    void mailerIsOffWithoutCredentialsAndNeverThrows() {
        Mailer off = new Mailer("", "", "Notch", "smtp.gmail.com", 587);
        assertFalse(off.configured());
        assertFalse(off.send("someone@example.com", "s", "b", null, null));
        Mailer spaced = new Mailer("me@gmail.com", "abcd efgh ijkl mnop", "Notch", "smtp.gmail.com", 587);
        assertTrue(spaced.configured());
        assertFalse(spaced.send(null, "s", "b", null, null), "no recipient, nothing sent");
    }
}
