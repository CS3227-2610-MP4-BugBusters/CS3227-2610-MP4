package mp4.platform;

import java.sql.*;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

final class AuthProvider implements AuthenticationProvider {
    private final javax.sql.DataSource source; private final PasswordEncoder encoder;
    private final String dummy = "{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());
    AuthProvider(javax.sql.DataSource source, PasswordEncoder encoder) { this.source=source; this.encoder=encoder; }
    public Authentication authenticate(Authentication input) throws AuthenticationException {
        String username=(String)input.getPrincipal(), password=(String)input.getCredentials();
        try (Connection c=source.getConnection(); PreparedStatement q=c.prepareStatement("SELECT id,display_name,password_hash,role,active,auth_generation FROM public.app_accounts WHERE username=?")) {
            q.setString(1,username); try(ResultSet r=q.executeQuery()) {
                if(!r.next()) { encoder.matches(password,dummy); throw new BadCredentialsException("bad credentials"); }
                String hash=r.getString("password_hash"); boolean matches=encoder.matches(password,hash);
                if(!matches || !r.getBoolean("active")) throw new BadCredentialsException("bad credentials");
                CurrentUser user=new CurrentUser(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("role"),r.getLong("auth_generation"));
                return new UsernamePasswordAuthenticationToken(user,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+user.role())));
            }
        } catch(BadCredentialsException e){throw e;} catch(SQLException e){throw new AuthenticationServiceException("authentication unavailable");}
    }
    public boolean supports(Class<?> type){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);}
    record CurrentUser(UUID id,String displayName,String role,long generation) implements java.io.Serializable, java.security.Principal {
        @Override public String getName(){return id.toString();}
    }
}
