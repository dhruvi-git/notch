package dev.notch.api.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The signed-in user, and personal tokens for the Chrome and VS Code extensions. */
@RestController
public class TokenController {

    public record CreateTokenRequest(@NotBlank @Size(max = 60) String name) {
    }

    private final AuthService auth;
    private final CurrentUser currentUser;

    public TokenController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me")
    public UserRepository.User me() {
        return auth.user(currentUser.id());
    }

    @GetMapping("/api/tokens")
    public List<TokenRepository.TokenInfo> list() {
        return auth.listPersonalTokens(currentUser.id());
    }

    /** The token is in this response only; it cannot be shown again. */
    @PostMapping("/api/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.NewToken create(@Valid @RequestBody CreateTokenRequest req) {
        return auth.createPersonalToken(currentUser.id(), req.name());
    }

    @DeleteMapping("/api/tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID tokenId) {
        auth.revokePersonalToken(currentUser.id(), tokenId);
    }
}
