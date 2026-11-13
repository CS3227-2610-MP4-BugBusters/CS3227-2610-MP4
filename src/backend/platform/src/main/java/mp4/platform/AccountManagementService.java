package mp4.platform;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import mp4.auth.UsernamePolicy;
import org.springframework.security.crypto.password.PasswordEncoder;

final class AccountManagementService {
    private final DataSource ds;
    private final PasswordEncoder encoder;
    AccountManagementService(DataSource ds, PasswordEncoder encoder) { this.ds=ds; this.encoder=encoder; }
    static final class Denied extends RuntimeException {
        final int status; final String code;
        Denied(int status,String code) { this.status=status; this.code=code; }
    }
    static String password(String value,int min) {
        try {
            int size=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining();
            if(size<min || size>72) throw new IllegalArgumentException();
            return value;
        } catch(java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException(); }
    }
    Map<String,Object> info(AuthProvider.CurrentUser actor) throws SQLException {
        try(var c=ds.getConnection();var s=c.prepareStatement("SELECT username,demo_locked FROM app_accounts WHERE id=? AND active AND auth_generation=?")) {
            s.setObject(1,actor.id());s.setLong(2,actor.generation());
            try(var row=s.executeQuery()) {
                if(!row.next()) throw new Denied(401,"UNAUTHENTICATED");
                return Map.of("id",actor.id().toString(),"username",row.getString(1),"displayName",actor.displayName(),"role",actor.role(),"demoLocked",row.getBoolean(2));
            }
        }
    }
    void changeDisplayName(AuthProvider.CurrentUser actor,String name) throws SQLException {
        if(name.isBlank() || name.length()>200 || !StandardCharsets.UTF_8.newEncoder().canEncode(name) || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        try(var c=ds.getConnection();var s=c.prepareStatement("UPDATE app_accounts SET display_name=? WHERE id=? AND active AND auth_generation=? AND NOT demo_locked AND id<>?")) {
            s.setString(1,name);s.setObject(2,actor.id());s.setLong(3,actor.generation());s.setObject(4,DemoAccountBootstrap.ID);
            if(s.executeUpdate()==1) return;
        }
        try(var c=ds.getConnection();var s=c.prepareStatement("SELECT active,auth_generation,demo_locked FROM app_accounts WHERE id=?")) {
            s.setObject(1,actor.id());
            try(var row=s.executeQuery()) {
                if(!row.next() || !row.getBoolean("active") || row.getLong("auth_generation")!=actor.generation()) throw new Denied(401,"UNAUTHENTICATED");
                if(row.getBoolean("demo_locked") || DemoAccountBootstrap.ID.equals(actor.id())) throw new Denied(403,"DEMO_ACCOUNT_LOCKED");
                throw new Denied(401,"UNAUTHENTICATED");
            }
        }
    }
    Map<String,Object> create(AuthProvider.CurrentUser actor,String username,String name,String role,String secret) throws SQLException {
        if(!"MAINTAINER".equals(actor.role())) throw new Denied(403,"FORBIDDEN");
        username=UsernamePolicy.normalize(username);
        if(name.isBlank() || name.length()>200 || name.codePoints().anyMatch(Character::isISOControl) || !Set.of("REPORTER","DEVELOPER","MAINTAINER").contains(role)) throw new IllegalArgumentException();
        String hash=encoder.encode(password(secret,12)); UUID id=UUID.randomUUID();
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.prepareStatement("SELECT role,active,auth_generation FROM app_accounts WHERE id=? FOR SHARE")) {
                    s.setObject(1,actor.id());
                    try(var row=s.executeQuery()) {
                        if(!row.next() || !row.getBoolean("active") || row.getLong("auth_generation")!=actor.generation()) throw new Denied(401,"UNAUTHENTICATED");
                        if(!"MAINTAINER".equals(row.getString("role"))) throw new Denied(403,"FORBIDDEN");
                    }
                }
                try(var s=c.prepareStatement("INSERT INTO app_accounts(id,username,display_name,password_hash,role) VALUES (?,?,?,?,?)")) {
                    s.setObject(1,id);s.setString(2,username);s.setString(3,name);s.setString(4,hash);s.setString(5,role);s.executeUpdate();
                }
                c.commit();
                return Map.of("id",id.toString(),"username",username,"displayName",name,"role",role,"active",true);
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                if(e instanceof SQLException sql && "23505".equals(sql.getSQLState())) throw new Denied(409,"ACCOUNT_EXISTS");
                throw e;
            }
        }
    }
    void changePassword(AuthProvider.CurrentUser actor,String current,String next) throws SQLException {
        password(current,1);password(next,12);
        var snapshot=new AuthAccountRepository(ds).findById(actor.id()).orElseThrow(()->new Denied(401,"UNAUTHENTICATED"));
        if(DemoAccountBootstrap.ID.equals(actor.id())) throw new Denied(403,"DEMO_ACCOUNT_LOCKED");
        if(!snapshot.active() || snapshot.generation()!=actor.generation()) throw new Denied(401,"UNAUTHENTICATED");
        if(!encoder.matches(current,snapshot.passwordHash())) throw new Denied(400,"CURRENT_PASSWORD_INVALID");
        String hash=encoder.encode(next);
        try(var c=ds.getConnection();var s=c.prepareStatement("UPDATE app_accounts SET password_hash=?,auth_generation=auth_generation+1 WHERE id=? AND active AND NOT demo_locked AND auth_generation=? AND password_hash=?")) {
            s.setString(1,hash);s.setObject(2,actor.id());s.setLong(3,actor.generation());s.setString(4,snapshot.passwordHash());
            if(s.executeUpdate()!=1) throw new Denied(401,"UNAUTHENTICATED");
        }
    }
}
