package mp4.platform;

import jakarta.servlet.*; import jakarta.servlet.http.*;
import java.io.*; import java.net.*; import java.nio.charset.*; import java.sql.*; import java.time.*; import java.util.*;
import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import javax.sql.DataSource;
import mp4.developer.*;
import org.springframework.beans.factory.annotation.Value; import org.springframework.http.*; import org.springframework.security.authentication.*; import org.springframework.security.core.*; import org.springframework.security.core.context.*; import org.springframework.security.web.context.*; import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy; import org.springframework.security.web.csrf.*; import org.springframework.web.bind.annotation.*;

@RestController
final class AuthController {
    private static final String CREATED="MP4_AUTH_CREATED", LAST="MP4_AUTH_LAST", USER="MP4_AUTH_USER";
    private final DataSource db; private final AuthenticationManager manager; private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts=new HttpSessionSecurityContextRepository(); private final HttpSessionCsrfTokenRepository csrfRepo;
    private final Clock clock; private final byte[] limiterSecret; private final AssignedReportListHttpHandler reports;
    private final boolean local; private final String publicOrigin;
    AuthController(DataSource db,AuthenticationManager manager,SessionAuthenticationStrategy sessionStrategy,HttpSessionCsrfTokenRepository csrfRepo,Clock clock,
            @Value("${mp4.auth.rate-limit-secret}") String secret,@Value("${mp4.auth.local:false}") boolean local,
            @Value("${mp4.auth.public-origin:}") String publicOrigin) {
        this.db=db;this.manager=manager;this.sessionStrategy=sessionStrategy;this.csrfRepo=csrfRepo;this.clock=clock;this.local=local;this.publicOrigin=publicOrigin;
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalArgumentException("rate limiter secret must be at least 32 UTF-8 bytes"); this.limiterSecret=secret.getBytes(StandardCharsets.UTF_8);
        this.reports=new AssignedReportListHttpHandler(new AssignedReportStore(db));
    }
    @GetMapping("/api/auth/csrf") Object csrf(HttpServletRequest req,HttpServletResponse res) {
        AuthHttp.noStore(res); CsrfToken token=(CsrfToken)req.getAttribute(CsrfToken.class.getName());
        if(token==null) return Map.of("token","","headerName","X-CSRF-TOKEN");
        req.getSession(true); return Map.of("token",token.getToken(),"headerName","X-CSRF-TOKEN");
    }
    @PostMapping(value="/api/auth/login", consumes="application/x-www-form-urlencoded") Object login(HttpServletRequest req,HttpServletResponse res) throws IOException {
        AuthHttp.noStore(res); if(!originAllowed(req)) { AuthHttp.error(res,403,"CSRF_INVALID","Request denied");return null; }
        Map<String,String> form;
        String normalized;
        try { form=parseForm(req); normalized=form.get("username").trim().toLowerCase(Locale.ROOT); if(normalized.length()<3||normalized.length()>64||!normalized.matches("[a-z0-9._-]+"))throw new IllegalArgumentException(); byte[] pw=form.get("password").getBytes(StandardCharsets.UTF_8); if(pw.length<1||pw.length>72)throw new IllegalArgumentException(); }
        catch(Exception malformed){AuthHttp.error(res,400,"VALIDATION_FAILED","Invalid authentication request");return null;}
        try { int admitted=admit(normalized,req.getRemoteAddr()); if(admitted>0){res.setHeader("Retry-After",Integer.toString(admitted));AuthHttp.error(res,429,"AUTH_RATE_LIMITED","Too many login attempts");return null;} }
        catch(Exception unavailable){AuthHttp.error(res,503,"STORAGE_UNAVAILABLE","Authentication is temporarily unavailable");return null;}
        String username=normalized, password=form.get("password");
        Authentication auth;
        try { auth=manager.authenticate(new UsernamePasswordAuthenticationToken(username,password)); }
        catch(BadCredentialsException bad){AuthHttp.error(res,401,"UNAUTHENTICATED","Credentials are invalid");return null;}
        catch(AuthenticationException unavailable){AuthHttp.error(res,503,"STORAGE_UNAVAILABLE","Authentication is temporarily unavailable");return null;}
        try {
            HttpSession session=req.getSession(true); sessionStrategy.onAuthentication(auth,req,res);
            AuthProvider.CurrentUser user=(AuthProvider.CurrentUser)auth.getPrincipal(); session.setAttribute(USER,user); session.setAttribute(CREATED,clock.millis());session.setAttribute(LAST,clock.millis());
            SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,req,res);
            return userJson(user,true);
        } catch(Exception e){AuthHttp.error(res,503,"STORAGE_UNAVAILABLE","Authentication is temporarily unavailable");return null;}
    }
    @GetMapping("/api/auth/me") Object me(HttpServletRequest req,HttpServletResponse res) throws IOException {
        AuthHttp.noStore(res); AuthProvider.CurrentUser user=current(req,res); return user==null?null:userJson(user,true);
    }
    @PostMapping("/api/auth/logout") void logout(HttpServletRequest req,HttpServletResponse res) throws IOException {
        AuthHttp.noStore(res); if(!originAllowed(req)){AuthHttp.error(res,403,"CSRF_INVALID","Request denied");return;}
        HttpSession s=req.getSession(false); if(s!=null)s.invalidate(); SecurityContextHolder.clearContext(); contexts.saveContext(SecurityContextHolder.createEmptyContext(),req,res);
        res.setStatus(204);
    }
    @RequestMapping("/api/developer/reports") void developer(HttpServletRequest req,HttpServletResponse res) throws IOException {
        AuthHttp.noStore(res);AuthProvider.CurrentUser user=current(req,res);if(user==null)return;
        if(!"DEVELOPER".equals(user.role())){AuthHttp.error(res,403,"FORBIDDEN","Active Developer access required");return;}
        reports.handle(new ServletExchange(req,res),new ChecklistDraftStore.Actor(user.id(),ChecklistDraftStore.Role.DEVELOPER,true));
    }
    private AuthProvider.CurrentUser current(HttpServletRequest req,HttpServletResponse res)throws IOException {
        Authentication a=SecurityContextHolder.getContext().getAuthentication(); if(a==null||!a.isAuthenticated()||!(a.getPrincipal() instanceof AuthProvider.CurrentUser user)){AuthHttp.error(res,401,"UNAUTHENTICATED","Authentication required");return null;}
        HttpSession session=req.getSession(false);if(session==null){AuthHttp.error(res,401,"UNAUTHENTICATED","Authentication required");return null;}
        Long created=(Long)session.getAttribute(CREATED),last=(Long)session.getAttribute(LAST);
        if(created==null||last==null||clock.millis()-created>=Duration.ofHours(8).toMillis()||clock.millis()-last>=Duration.ofMinutes(30).toMillis()){session.invalidate();SecurityContextHolder.clearContext();AuthHttp.error(res,401,"UNAUTHENTICATED","Authentication required");return null;}
        try(Connection c=db.getConnection();PreparedStatement q=c.prepareStatement("SELECT active,auth_generation,role,display_name FROM public.app_accounts WHERE id=?")){q.setObject(1,user.id());try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong("auth_generation")!=user.generation()){session.invalidate();SecurityContextHolder.clearContext();AuthHttp.error(res,401,"UNAUTHENTICATED","Authentication required");return null;}if(!r.getBoolean("active")){session.invalidate();SecurityContextHolder.clearContext();AuthHttp.error(res,403,"FORBIDDEN","Account is inactive");return null;} AuthProvider.CurrentUser fresh=new AuthProvider.CurrentUser(user.id(),r.getString("display_name"),r.getString("role"),user.generation());session.setAttribute(LAST,clock.millis());return fresh;}}
        catch(SQLException e){AuthHttp.error(res,503,"STORAGE_UNAVAILABLE","Authentication is temporarily unavailable");return null;}
    }
    private static Map<String,Object> userJson(AuthProvider.CurrentUser user,boolean active){return Map.of("id",user.id().toString(),"displayName",user.displayName(),"role",user.role(),"active",active);}
    private boolean originAllowed(HttpServletRequest req){String origin=req.getHeader("Origin");if(origin==null)return true;if(!publicOrigin.isBlank()&&publicOrigin.equals(origin))return true;try{URI o=URI.create(origin);return o.getHost()!=null&&o.getHost().equalsIgnoreCase(req.getServerName())&&effectivePort(o)==req.getServerPort()&&Objects.equals(o.getScheme(),req.getScheme());}catch(Exception e){return false;}}
    private static int effectivePort(URI u){return u.getPort()>=0?u.getPort():"https".equals(u.getScheme())?443:80;}
    private static Map<String,String> parseForm(HttpServletRequest req)throws Exception{byte[] b=req.getInputStream().readNBytes(8193);if(b.length>8192)throw new IllegalArgumentException();String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();Map<String,String> out=new HashMap<>();for(String part:raw.split("&",-1)){int x=part.indexOf('=');String k=URLDecoder.decode(x<0?part:part.substring(0,x),StandardCharsets.UTF_8),v=URLDecoder.decode(x<0?"":part.substring(x+1),StandardCharsets.UTF_8);if(!Set.of("username","password").contains(k)||out.putIfAbsent(k,v)!=null)throw new IllegalArgumentException();}if(!out.keySet().equals(Set.of("username","password")))throw new IllegalArgumentException();return out;}
    private int admit(String username,String address)throws Exception{String u=digest("user:"+username),ip=digest("client:"+address);Instant now=clock.instant();try(Connection c=db.getConnection()){c.setAutoCommit(false);try{for(String k:new TreeSet<>(List.of(u,ip)))try(PreparedStatement l=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){l.setString(1,k);l.execute();}try(PreparedStatement d=c.prepareStatement("DELETE FROM public.mp4_auth_login_attempt WHERE attempted_at < ?")){d.setTimestamp(1,Timestamp.from(now.minus(Duration.ofHours(24))));d.executeUpdate();}int uc=count(c,u,now),ic=count(c,ip,now);if(uc>=10||ic>=100){c.rollback();return retry(c==null?0:Math.max(retryFor(u,now),retryFor(ip,now)));}try(PreparedStatement ins=c.prepareStatement("INSERT INTO public.mp4_auth_login_attempt(key_digest,attempted_at) VALUES (?,?),(?,?)")){ins.setString(1,u);ins.setTimestamp(2,Timestamp.from(now));ins.setString(3,ip);ins.setTimestamp(4,Timestamp.from(now));ins.executeUpdate();}c.commit();return 0;}catch(Exception e){c.rollback();throw e;}}}
    private int count(Connection c,String key,Instant now)throws SQLException{try(PreparedStatement q=c.prepareStatement("SELECT count(*) FROM public.mp4_auth_login_attempt WHERE key_digest=? AND attempted_at >= ?")){q.setString(1,key);q.setTimestamp(2,Timestamp.from(now.minus(Duration.ofMinutes(15))));try(ResultSet r=q.executeQuery()){r.next();return r.getInt(1);}}}
    private int retryFor(String key,Instant now)throws SQLException{try(Connection c=db.getConnection();PreparedStatement q=c.prepareStatement("SELECT min(attempted_at) FROM public.mp4_auth_login_attempt WHERE key_digest=? AND attempted_at >= ?")){q.setString(1,key);q.setTimestamp(2,Timestamp.from(now.minus(Duration.ofMinutes(15))));try(ResultSet r=q.executeQuery()){r.next();Timestamp t=r.getTimestamp(1);return t==null?1:(int)Math.max(1,Duration.between(now,t.toInstant().plus(Duration.ofMinutes(15))).getSeconds());}}}
    private static int retry(int n){return Math.min(900,Math.max(1,n));}
    private String digest(String value)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(limiterSecret,"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
    private static final class ServletExchange extends com.sun.net.httpserver.HttpExchange {
        private final HttpServletRequest req;private final HttpServletResponse res;private final com.sun.net.httpserver.Headers headers=new com.sun.net.httpserver.Headers();
        ServletExchange(HttpServletRequest r,HttpServletResponse s){req=r;res=s;}
        public com.sun.net.httpserver.Headers getRequestHeaders(){return headers;}public com.sun.net.httpserver.Headers getResponseHeaders(){return headers;}public URI getRequestURI(){try{return URI.create(req.getRequestURI()+(req.getQueryString()==null?"":"?"+req.getQueryString()));}catch(Exception e){return URI.create("/");}}public String getRequestMethod(){return req.getMethod();}public com.sun.net.httpserver.HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){try{return req.getInputStream();}catch(IOException e){throw new UncheckedIOException(e);}}public OutputStream getResponseBody(){try{return res.getOutputStream();}catch(IOException e){throw new UncheckedIOException(e);}}public void sendResponseHeaders(int code,long length)throws IOException{res.setStatus(code);headers.forEach((k,v)->{if(!v.isEmpty())res.setHeader(k,v.get(0));});res.setContentType("application/json; charset=utf-8");res.setHeader("Cache-Control","no-store");}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress(req.getRemoteAddr(),req.getRemotePort());}public int getResponseCode(){return res.getStatus();}public InetSocketAddress getLocalAddress(){return new InetSocketAddress(req.getLocalAddr(),req.getLocalPort());}public String getProtocol(){return req.getProtocol();}public Object getAttribute(String n){return req.getAttribute(n);}public void setAttribute(String n,Object v){req.setAttribute(n,v);}public void setStreams(InputStream i,OutputStream o){}public com.sun.net.httpserver.HttpPrincipal getPrincipal(){return null;}
    }
}
