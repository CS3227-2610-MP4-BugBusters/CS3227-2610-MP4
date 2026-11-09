package mp4.platform;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

final class AuthProvider implements AuthenticationProvider {
    private final AuthAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    AuthProvider(AuthAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.dummyHash = "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                .encode(UUID.randomUUID().toString());
    }

    @Override public Authentication authenticate(Authentication input) throws AuthenticationException {
        String username = (String) input.getPrincipal();
        String password = (String) input.getCredentials();
        try {
            var snapshot = accounts.findByUsername(username);
            if (snapshot.isEmpty()) {
                encoder.matches(password, dummyHash);
                throw new BadCredentialsException("bad credentials");
            }
            AuthAccountRepository.AccountSnapshot account = snapshot.get();
            if (!encoder.matches(password, account.passwordHash())) throw new BadCredentialsException("bad credentials");
            if (!accounts.confirmCredentialSnapshot(account)) throw new BadCredentialsException("bad credentials");

            CurrentUser user = new CurrentUser(account.id(), account.displayName(), account.role(), account.generation());
            return new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        } catch (BadCredentialsException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new AuthenticationServiceException("authentication unavailable");
        }
    }

    @Override public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }

    record CurrentUser(UUID id, String displayName, String role, long generation)
            implements java.io.Serializable, java.security.Principal {
        @Override public String getName() { return id.toString(); }
    }
}
