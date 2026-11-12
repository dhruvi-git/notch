package dev.notch.engine.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @Test
    void redactsOpenAiStyleKey() {
        var r = scanner.scan("use OPENAI_API_KEY=sk-proj-abc123DEF456ghi789JKL now");
        assertEquals(1, r.findings().size());
        assertEquals("openai-key", r.findings().get(0).type());
        assertEquals("use OPENAI_API_KEY=[REDACTED:openai-key] now", r.redacted());
    }

    @Test
    void anthropicKeyIsLabelledAsSuch() {
        var r = scanner.scan("key: sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx");
        assertEquals("anthropic-key", r.findings().get(0).type());
    }

    @Test
    void redactsAwsAccessKey() {
        var r = scanner.scan("aws_access_key_id = AKIAIOSFODNN7EXAMPLE");
        assertEquals("aws-access-key", r.findings().get(0).type());
        assertFalse(r.redacted().contains("AKIA"));
    }

    @Test
    void ignoresPrefixInsideOrdinaryWords() {
        var r = scanner.scan("a task-based approach and a risk-free plan");
        assertTrue(r.findings().isEmpty());
        assertEquals("a task-based approach and a risk-free plan", r.redacted());
    }

    @Test
    void redactsWholePrivateKeyBlock() {
        String text = "before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nabc\n-----END RSA PRIVATE KEY-----\nafter";
        var r = scanner.scan(text);
        assertEquals("before\n[REDACTED:private-key]\nafter", r.redacted());
    }

    @Test
    void publicCertificateIsNotRedacted() {
        var r = scanner.scan("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----");
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void emptyTextHasNoFindings() {  // boundary
        var r = scanner.scan("");
        assertTrue(r.findings().isEmpty());
        assertEquals("", r.redacted());
    }

    @Test
    void flagsHighEntropyTokenButNotGitHash() {
        String token = "Zx9Kq2Lm7Np4Rt8Vw1Ys6Bc3Df5Gh0JkQ";
        String sha = "3f786850e387550fdab836ed7e6dc881de23001b";
        var r = scanner.scan("token " + token + " commit " + sha);
        assertEquals(1, r.findings().size());
        assertEquals("high-entropy-string", r.findings().get(0).type());
        assertTrue(r.redacted().contains(sha));
    }

    @Test
    void shortKeyLikeStringsAreNotRedacted() {
        assertTrue(scanner.scan("sk-short").findings().isEmpty());
    }

    @Test
    void urlPathsAreNotSecretsButTheirQueryStringsAre() {
        SecretScanner scanner = new SecretScanner();
        String trailer = "Claude-Session: https://claude.ai/code/session_01AbCdEfGh2IjKlMn3OpQrStUv4WxYz";
        assertEquals(trailer, scanner.scan(trailer).redacted(), "a session link isn't a secret");
        String leak = "curl https://api.example.com/v1/data?token=Zx8Kq2Lm9Vb4Nc7Rt1Yw6Pd3Hs5Gf0Ja";
        assertTrue(scanner.scan(leak).redacted().contains("[REDACTED:"), "a token in a query string is");
        String key = "see https://github.com/x?y=1 and ghp_abcdefghijklmnopqrstuvwxyzABCDEF123456";
        assertTrue(scanner.scan(key).redacted().contains("[REDACTED:"), "known key formats are caught anywhere");
    }
}
