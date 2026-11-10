package mp4.platform;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableJdbcHttpSession(tableName = "mp4_auth_session", maxInactiveIntervalInSeconds = 1800)
@EnableScheduling
public class AuthConfiguration {
    @Bean WebMvcConfigurer developerFrontendRoute() {
        return new WebMvcConfigurer() {
            @Override public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/developer/").setViewName("forward:/developer/index.html");
            }
        };
    }

    @Bean DataSource authDataSource(@Value("${mp4.auth.jdbc-url}") String url,
            @Value("${mp4.auth.db-user}") String user, @Value("${mp4.auth.db-password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver"); ds.setUrl(url); ds.setUsername(user); ds.setPassword(password);
        return ds;
    }
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean InitializingBean authMigrations(DataSource ds) { return () -> {
        Flyway.configure().dataSource(ds).defaultSchema("public").locations("classpath:db/auth").table("mp4_auth_schema_history").baselineOnMigrate(true).baselineVersion("99").load().migrate();
        mp4.maintainer.storage.SharedSchemaBootstrap.initialize(ds);
        Flyway.configure().dataSource(ds).schemas("mp4_socllm").defaultSchema("mp4_socllm").locations("classpath:db/socllm")
                .table("mp4_socllm_schema_history").load().migrate();
    }; }
    @Bean CookieSerializer sessionCookieSerializer(@Value("${mp4.auth.local:false}") boolean local,
            @Value("${server.address:127.0.0.1}") String bind) {
        boolean loopback=bind.equals("127.0.0.1")||bind.equals("::1")||bind.equals("localhost");
        if(local&&!loopback) throw new IllegalArgumentException("local cookies require a loopback bind");
        DefaultCookieSerializer serializer=new DefaultCookieSerializer();serializer.setCookieName("MP4SESSION");serializer.setCookiePath("/");serializer.setSameSite("Lax");serializer.setUseSecureCookie(!local);serializer.setUseHttpOnlyCookie(true);return serializer;
    }
    @Bean DelegatingPasswordEncoder authPasswordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }
    @Bean AuthAccountRepository authAccountRepository(DataSource ds) { return new AuthAccountRepository(ds); }
    @Bean AuthProvider authProvider(AuthAccountRepository accounts, DelegatingPasswordEncoder encoder) { return new AuthProvider(accounts, encoder); }
    @Bean LoginAttemptStore loginAttemptStore(DataSource ds, Clock clock,
            @Value("${mp4.auth.rate-limit-secret}") String secret) { return new LoginAttemptStore(ds, clock, secret); }
    @Bean AuthenticationManager authManager(AuthProvider provider) { return new ProviderManager(provider); }
    @Bean AuthSessionFreshnessFilter authSessionFreshnessFilter(AuthAccountRepository accounts, Clock clock) {
        return new AuthSessionFreshnessFilter(accounts, clock);
    }
    @Bean FilterRegistrationBean<AuthSessionFreshnessFilter> disableContainerFreshnessRegistration(AuthSessionFreshnessFilter filter) {
        FilterRegistrationBean<AuthSessionFreshnessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean FilterRegistrationBean<SessionFailureBoundaryFilter> sessionFailureBoundary() {
        FilterRegistrationBean<SessionFailureBoundaryFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SessionFailureBoundaryFilter());
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
    @Bean FilterRegistrationBean<EagerSessionReadFilter> eagerSessionRead() {
        FilterRegistrationBean<EagerSessionReadFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new EagerSessionReadFilter());
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 60);
        return registration;
    }
    @Bean SessionAuthenticationStrategy authSessionStrategy(HttpSessionCsrfTokenRepository csrf) {
        return new org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy(
            java.util.List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() {
        HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository(); repo.setHeaderName("X-CSRF-TOKEN"); return repo;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionCsrfTokenRepository csrf,
            AuthSessionFreshnessFilter freshness) throws Exception {
        return http.csrf(c -> c.csrfTokenRepository(csrf))
            .authorizeHttpRequests(r -> r.requestMatchers("/api/health", "/api/auth/**", "/", "/index.html", "/assets/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/developer", "/developer/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/reports/*/checklist/draft").hasRole("DEVELOPER")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/reports/*/checklist/draft").hasRole("DEVELOPER")
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/reports/*/checklist/share").hasRole("DEVELOPER")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/reports/*/checklist/shared").hasAnyRole("REPORTER", "DEVELOPER", "MAINTAINER")
                .requestMatchers("/api/developer/**").hasRole("DEVELOPER")
                .requestMatchers("/api/maintainer/**").hasRole("MAINTAINER").anyRequest().denyAll())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> AuthHttp.error(res,401,"UNAUTHENTICATED","Authentication required"))
                .accessDeniedHandler((req,res,ex) -> {
                    boolean csrfFailure=ex instanceof org.springframework.security.web.csrf.CsrfException;
                    AuthHttp.error(res,403,csrfFailure?"CSRF_INVALID":"FORBIDDEN",csrfFailure?"Request denied":"Access denied");
                }))
            .addFilterAfter(freshness, SecurityContextHolderFilter.class)
            .build();
    }
}
