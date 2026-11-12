package mp4.platform;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
final class AccountManagementController {
    private final AccountManagementService service;
    private final AuthController auth;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    AccountManagementController(DataSource ds,PasswordEncoder encoder,AuthController auth) {
        this.service=new AccountManagementService(ds,encoder);this.auth=auth;
    }
    private AuthProvider.CurrentUser actor() {
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null || !(a.getPrincipal() instanceof AuthProvider.CurrentUser user)) throw new AccountManagementService.Denied(401,"UNAUTHENTICATED");
        return user;
    }
    private Map<String,String> input(HttpServletRequest request,Set<String> keys) throws IOException {
        if(request.getQueryString()!=null || !auth.originAllowed(request)) throw new AccountManagementService.Denied(403,"CSRF_INVALID");
        byte[] bytes=request.getInputStream().readNBytes(8193);
        if(bytes.length>8192) throw new IllegalArgumentException();
        try {
            JsonNode root=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if(root==null || !root.isObject() || root.size()!=keys.size()) throw new IllegalArgumentException();
            Map<String,String> fields=new HashMap<>();
            for(String key:keys) {
                if(!root.has(key) || !root.get(key).isTextual()) throw new IllegalArgumentException();
                fields.put(key,root.get(key).textValue());
            }
            return fields;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(); }
    }
    @GetMapping("/api/account") Object info(HttpServletRequest req,HttpServletResponse res) throws IOException {
        return execute(res,()-> {
            if(req.getQueryString()!=null || req.getInputStream().readNBytes(1).length!=0) throw new IllegalArgumentException();
            return service.info(actor());
        });
    }
    @PostMapping(value="/api/maintainer/accounts",consumes="application/json") Object create(HttpServletRequest req,HttpServletResponse res) throws IOException {
        return execute(res,()-> {
            var f=input(req,Set.of("username","displayName","role","password"));
            var result=service.create(actor(),f.get("username"),f.get("displayName"),f.get("role"),f.get("password"));
            res.setStatus(201);return result;
        });
    }
    @PostMapping(value="/api/account/password",consumes="application/json") Object password(HttpServletRequest req,HttpServletResponse res) throws IOException {
        return execute(res,()-> {
            var f=input(req,Set.of("currentPassword","newPassword"));
            service.changePassword(actor(),f.get("currentPassword"),f.get("newPassword"));
            var session=req.getSession(false);if(session!=null) session.invalidate();
            SecurityContextHolder.clearContext();res.setStatus(204);return null;
        });
    }
    private Object execute(HttpServletResponse response,Action action) throws IOException {
        AuthHttp.noStore(response);
        try { return action.run(); }
        catch(AccountManagementService.Denied e) { AuthHttp.error(response,e.status,e.code,switch(e.code) {
            case "CURRENT_PASSWORD_INVALID" -> "Current password is incorrect";
            case "DEMO_ACCOUNT_LOCKED" -> "The shared demo account cannot be changed";
            case "ACCOUNT_EXISTS" -> "Username is already in use";
            default -> "Access denied";
        }); }
        catch(IllegalArgumentException e) { AuthHttp.error(response,400,"VALIDATION_FAILED","Check the account fields and password length (12–72 UTF-8 bytes)"); }
        catch(java.sql.SQLException e) { AuthHttp.error(response,503,"STORAGE_UNAVAILABLE","Account service is temporarily unavailable"); }
        return null;
    }
    @FunctionalInterface interface Action { Object run() throws IOException,java.sql.SQLException; }
}
