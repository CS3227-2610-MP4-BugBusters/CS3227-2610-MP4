package mp4.socllm;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import mp4.maintainer.summary.SummaryContracts.*;
import mp4.platform.SocLlmSuggestionProvider;
import mp4.platform.SuggestionProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable quota and lease checks. Run only through the disposable PostgreSQL setup runner. */
class PostgresSocLlmAdmissionTest {
    private static DataSource source;
    private static JdbcTemplate jdbc;

    @BeforeAll static void prepareSchema() {
        String url=System.getenv("MP4_AUTH_TEST_JDBC_URL"),user=System.getenv("MP4_AUTH_TEST_DB_USER"),password=System.getenv("MP4_AUTH_TEST_DB_PASSWORD");
        assertNotNull(url,"test requires disposable PostgreSQL fixture");
        DriverManagerDataSource ds=new DriverManagerDataSource(url,user,password);source=ds;jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas("mp4_socllm").defaultSchema("mp4_socllm")
                .locations("classpath:db/socllm").table("mp4_socllm_schema_history").load().migrate();
    }

    @BeforeEach void clearAttempts(){jdbc.update("DELETE FROM mp4_socllm.attempt");jdbc.update("DELETE FROM mp4_socllm.provider_budget");}

    @Test void successfulDeveloperSuggestionUsesRealRoleServiceAndDurableAdmission() {
        String developerKey = "developer-adapter-test-key";
        UUID actorId = UUID.randomUUID();
        String generated = "{\"steps\":[\"Inspect the authentication response\"]}";
        java.util.concurrent.atomic.AtomicReference<String> usedInstructions = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> usedInput = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger reservedOutputTokens = new java.util.concurrent.atomic.AtomicInteger();

        try (var admission = admission(Map.of(SocLlmContracts.Role.DEVELOPER,
                new PostgresSocLlmAdmission.KeyBudget(developerKey, 10, 10_000)));
             var roleService = new SocLlmRoleService(SocLlmContracts.Role.DEVELOPER, admission,
                (instructions, input, tokens, deadline) -> {
                    usedInstructions.set(instructions);
                    usedInput.set(input);
                    reservedOutputTokens.set(tokens);
                    return generated;
                })) {
            SuggestionProvider provider = new SocLlmSuggestionProvider(roleService, developerKey, "test-model");
            SuggestionProvider.ProviderResponse result = provider.suggest(actorId, "Login button hangs",
                    "Valid login leaves the page unchanged.", List.of("Reproduce with valid credentials"));

            assertEquals("[\"Inspect the authentication response\"]", result.rawOutput());
            assertTrue(usedInstructions.get().toLowerCase().contains("untrusted"));
            assertTrue(usedInput.get().contains("Login button hangs"));
            assertTrue(usedInput.get().contains("Reproduce with valid credentials"));
            assertEquals(384, reservedOutputTokens.get());

            Map<String, Object> attempt = jdbc.queryForMap("""
                    SELECT actor_id, role, task, reserved_tokens, in_flight_until
                    FROM mp4_socllm.attempt WHERE actor_id=?
                    """, actorId);
            assertEquals(actorId, attempt.get("actor_id"));
            assertEquals("DEVELOPER", attempt.get("role"));
            assertEquals("DEVELOPER_ASSIST", attempt.get("task"));
            assertEquals(usedInstructions.get().getBytes(StandardCharsets.UTF_8).length
                    + usedInput.get().getBytes(StandardCharsets.UTF_8).length + reservedOutputTokens.get(),
                    attempt.get("reserved_tokens"));
            assertNull(attempt.get("in_flight_until"), "successful completion must release the durable lease");
        }
    }

    @Test void separateInstancesSerializeSharedKeyBudgetAcrossRoleAliases() throws Exception {
        String sharedSecret="test-shared-key";
        var first=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(sharedSecret,1,2048),
                SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(sharedSecret,5,9000)));
        var second=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(sharedSecret,1,2048),
                SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(sharedSecret,5,9000)));
        UUID actor1=UUID.randomUUID(),actor2=UUID.randomUUID();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->attempt(first,SocLlmContracts.Role.REPORTER,actor1,start));
            var b=pool.submit(()->attempt(second,SocLlmContracts.Role.DEVELOPER,actor2,start));
            start.countDown();List<Object> outcomes=List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(AdmissionLease.class::isInstance).count());
            AdmissionLease granted=(AdmissionLease)outcomes.stream().filter(AdmissionLease.class::isInstance).findFirst().orElseThrow();granted.close();
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mp4_socllm.attempt",Integer.class));
            var denied=assertThrows(SummaryFailure.class,()->second.acquire(SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,denied.failureCode());
        } finally {pool.shutdownNow();first.close();second.close();}
    }

    @Test void everyAdmittedStartCountsAndLeaseBlocksUntilWorkerCloses() {
        try(var admission=admission(Map.of(SocLlmContracts.Role.MAINTAINER,new PostgresSocLlmAdmission.KeyBudget("maintainer",10,20_000)))) {
            UUID actor=UUID.randomUUID();
            for(int i=0;i<3;i++) {AdmissionLease lease=admission.acquire(SocLlmContracts.Role.MAINTAINER,actor,SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY,100,Duration.ofSeconds(60));lease.renewUntilWorkerTerminates();lease.close();}
            SummaryFailure limited=assertThrows(SummaryFailure.class,()->admission.acquire(SocLlmContracts.Role.MAINTAINER,actor,SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,limited.failureCode());
            UUID other=UUID.randomUUID();AdmissionLease worker=admission.acquire(SocLlmContracts.Role.MAINTAINER,other,SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY,100,Duration.ofSeconds(60));
            worker.renewUntilWorkerTerminates();
            jdbc.update("UPDATE mp4_socllm.attempt SET in_flight_until=clock_timestamp()-interval '2 minutes' WHERE actor_id=?", other);
            SummaryFailure busy=assertThrows(SummaryFailure.class,()->admission.acquire(SocLlmContracts.Role.MAINTAINER,other,SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_BUSY,busy.failureCode());worker.close();
            AdmissionLease afterClose=assertDoesNotThrow(()->admission.acquire(SocLlmContracts.Role.MAINTAINER,other,SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY,100,Duration.ofSeconds(60)));
            afterClose.close();
        }
    }

    @Test void unavailableDatabaseFailsClosedBeforeAnyAdmission() {
        var broken=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/missing","nobody","bad");
        try(var admission=admission(broken,Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget("reporter",5,2048)))) {
            SummaryFailure failure=assertThrows(SummaryFailure.class,()->admission.acquire(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_UNAVAILABLE,failure.failureCode());
        }
    }

    @Test void divergentReplicasCanOnlyTightenPersistedPhysicalKeyBudgets() {
        String requestKey="divergent-rpm-key";
        var permissive=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(requestKey,5,10_000)));
        var restrictive=admission(Map.of(SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(requestKey,1,10_000)));
        try {
            permissive.registerConfiguredBudgets();
            restrictive.registerConfiguredBudgets();
            AdmissionLease first=permissive.acquire(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,100,Duration.ofSeconds(60));
            first.close();
            SummaryFailure rpm=assertThrows(SummaryFailure.class,()->permissive.acquire(
                    SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,rpm.failureCode());
        } finally {permissive.close();restrictive.close();}

        jdbc.update("DELETE FROM mp4_socllm.attempt");jdbc.update("DELETE FROM mp4_socllm.provider_budget");
        String tokenKey="divergent-tpm-key";
        var highTokens=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(tokenKey,10,2_000)));
        var lowTokens=admission(Map.of(SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(tokenKey,10,1_000)));
        try {
            highTokens.registerConfiguredBudgets();
            lowTokens.registerConfiguredBudgets();
            AdmissionLease first=highTokens.acquire(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,1_000,Duration.ofSeconds(60));
            first.close();
            SummaryFailure tpm=assertThrows(SummaryFailure.class,()->highTokens.acquire(
                    SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,1,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,tpm.failureCode());
        } finally {highTokens.close();lowTokens.close();}

        jdbc.update("DELETE FROM mp4_socllm.attempt");jdbc.update("DELETE FROM mp4_socllm.provider_budget");
        String reverseKey="reverse-divergence-key";
        var restrictiveFirst=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(reverseKey,1,1_000)));
        var permissiveSecond=admission(Map.of(SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(reverseKey,5,2_000)));
        try {
            restrictiveFirst.registerConfiguredBudgets();
            permissiveSecond.registerConfiguredBudgets();
            AdmissionLease first=permissiveSecond.acquire(SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,1_000,Duration.ofSeconds(60));
            first.close();
            SummaryFailure tpm=assertThrows(SummaryFailure.class,()->permissiveSecond.acquire(
                    SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,1,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,tpm.failureCode());
        } finally {restrictiveFirst.close();permissiveSecond.close();}
    }

    @Test void whitespacePaddedAliasesShareRequestTokenAndInFlightBudgets() {
        String requestKey="canonical-rpm-alias";
        var paddedRequests=admission(Map.of(SocLlmContracts.Role.REPORTER,
                new PostgresSocLlmAdmission.KeyBudget("  "+requestKey+" \t",1,1_000)));
        var canonicalRequests=admission(Map.of(SocLlmContracts.Role.DEVELOPER,
                new PostgresSocLlmAdmission.KeyBudget(requestKey,5,5_000)));
        try {
            AdmissionLease first=paddedRequests.acquire(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),
                    SocLlmContracts.Task.REPORTER_ASSIST,100,Duration.ofSeconds(60));
            first.close();
            SummaryFailure rpm=assertThrows(SummaryFailure.class,()->canonicalRequests.acquire(
                    SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,
                    100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,rpm.failureCode());
        } finally {paddedRequests.close();canonicalRequests.close();}

        jdbc.update("DELETE FROM mp4_socllm.attempt");jdbc.update("DELETE FROM mp4_socllm.provider_budget");
        String tokenKey="canonical-tpm-alias";
        var paddedTokens=admission(Map.of(SocLlmContracts.Role.REPORTER,
                new PostgresSocLlmAdmission.KeyBudget(" \t"+tokenKey+" ",5,100)));
        var canonicalTokens=admission(Map.of(SocLlmContracts.Role.DEVELOPER,
                new PostgresSocLlmAdmission.KeyBudget(tokenKey,5,1_000)));
        try {
            AdmissionLease first=paddedTokens.acquire(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),
                    SocLlmContracts.Task.REPORTER_ASSIST,80,Duration.ofSeconds(60));
            first.close();
            SummaryFailure tpm=assertThrows(SummaryFailure.class,()->canonicalTokens.acquire(
                    SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,
                    30,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_RATE_LIMITED,tpm.failureCode());
        } finally {paddedTokens.close();canonicalTokens.close();}

        jdbc.update("DELETE FROM mp4_socllm.attempt");jdbc.update("DELETE FROM mp4_socllm.provider_budget");
        String workerKey="canonical-inflight-alias";
        var paddedWorkers=admission(Map.of(SocLlmContracts.Role.REPORTER,
                new PostgresSocLlmAdmission.KeyBudget(" "+workerKey+" ",10,10_000)));
        var canonicalWorkers=admission(Map.of(SocLlmContracts.Role.DEVELOPER,
                new PostgresSocLlmAdmission.KeyBudget(workerKey,10,10_000)));
        List<AdmissionLease> leases=new ArrayList<>();
        try {
            for(int index=0;index<4;index++) {
                boolean usePadded=index%2==0;
                leases.add((usePadded?paddedWorkers:canonicalWorkers).acquire(
                        usePadded?SocLlmContracts.Role.REPORTER:SocLlmContracts.Role.DEVELOPER,
                        UUID.randomUUID(),usePadded?SocLlmContracts.Task.REPORTER_ASSIST:
                                SocLlmContracts.Task.DEVELOPER_ASSIST,100,Duration.ofSeconds(60)));
            }
            SummaryFailure busy=assertThrows(SummaryFailure.class,()->paddedWorkers.acquire(
                    SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,
                    100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_BUSY,busy.failureCode());
        } finally {leases.forEach(AdmissionLease::close);paddedWorkers.close();canonicalWorkers.close();}
    }

    @Test void capFourAndAmbientTransactionOrMissingLeaseBothFailBeforeProvider() {
        String secret="four-worker-key";
        var admission=admission(Map.of(SocLlmContracts.Role.REPORTER,new PostgresSocLlmAdmission.KeyBudget(secret,10,100_000),
                SocLlmContracts.Role.DEVELOPER,new PostgresSocLlmAdmission.KeyBudget(secret,10,100_000)));
        List<AdmissionLease> leases=new ArrayList<>();
        try {
            admission.registerConfiguredBudgets();
            for(int i=0;i<4;i++) {
                SocLlmContracts.Role role=i%2==0?SocLlmContracts.Role.REPORTER:SocLlmContracts.Role.DEVELOPER;
                SocLlmContracts.Task task=role==SocLlmContracts.Role.REPORTER?SocLlmContracts.Task.REPORTER_ASSIST:SocLlmContracts.Task.DEVELOPER_ASSIST;
                leases.add(admission.acquire(role,UUID.randomUUID(),task,100,Duration.ofSeconds(60)));
            }
            jdbc.update("UPDATE mp4_socllm.attempt SET in_flight_until=clock_timestamp()-interval '2 minutes'");
            SummaryFailure fifth=assertThrows(SummaryFailure.class,()->admission.acquire(
                    SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,100,Duration.ofSeconds(60)));
            assertEquals(FailureCode.AI_BUSY,fifth.failureCode());
            leases.forEach(AdmissionLease::close);
            leases.clear();

            java.util.concurrent.atomic.AtomicInteger providerCalls=new java.util.concurrent.atomic.AtomicInteger();
            try(var gateway=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,
                    (instructions,input,tokens,deadline)->{providerCalls.incrementAndGet();return "unexpected";})) {
                TransactionTemplate callerTransaction=new TransactionTemplate(new DataSourceTransactionManager(source));
                int startsBeforeRejectedCall=jdbc.queryForObject("SELECT count(*) FROM mp4_socllm.attempt",Integer.class);
                callerTransaction.executeWithoutResult(status->{
                    SummaryFailure ambient=assertThrows(SummaryFailure.class,()->gateway.generate(
                            SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,
                            "trusted","data",100,Duration.ofSeconds(2)));
                    assertEquals(FailureCode.AI_UNAVAILABLE,ambient.failureCode());
                    status.setRollbackOnly();
                });
                assertEquals(0,providerCalls.get());
                assertEquals(startsBeforeRejectedCall,jdbc.queryForObject("SELECT count(*) FROM mp4_socllm.attempt",Integer.class),
                        "caller-owned rollback cannot erase or add durable attempts");
            }

            UUID visibleActor=UUID.randomUUID();
            java.util.concurrent.atomic.AtomicInteger committedBeforeProvider=new java.util.concurrent.atomic.AtomicInteger();
            try(var gateway=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,
                    (instructions,input,tokens,deadline)->{
                        committedBeforeProvider.set(jdbc.queryForObject("SELECT count(*) FROM mp4_socllm.attempt WHERE actor_id=?",Integer.class,visibleActor));
                        return "safe local fixture";
                    })) {
                assertEquals("safe local fixture",gateway.generate(SocLlmContracts.Role.REPORTER,visibleActor,
                        SocLlmContracts.Task.REPORTER_ASSIST,"trusted","data",100,Duration.ofSeconds(2)));
                assertEquals(1,committedBeforeProvider.get(),"admission is committed before provider work starts");
            }

            SocLlmContracts.RoleAdmission deletedLease=(role,actor,task,tokens,leaseDuration)->{
                AdmissionLease lease=admission.acquire(role,actor,task,tokens,leaseDuration);
                jdbc.update("DELETE FROM mp4_socllm.attempt WHERE actor_id=?",actor);
                return lease;
            };
            try(var gateway=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,deletedLease,
                    (instructions,input,tokens,deadline)->{providerCalls.incrementAndGet();return "unexpected";})) {
                SummaryFailure missing=assertThrows(SummaryFailure.class,()->gateway.generate(
                        SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,
                        "trusted","data",100,Duration.ofSeconds(2)));
                assertEquals(FailureCode.AI_UNAVAILABLE,missing.failureCode());
                assertEquals(0,providerCalls.get());
            }
        } finally {leases.forEach(AdmissionLease::close);admission.close();}
    }

    private static Object attempt(PostgresSocLlmAdmission admission,SocLlmContracts.Role role,UUID actor,CountDownLatch start) {
        try {start.await();return admission.acquire(role,actor,role==SocLlmContracts.Role.REPORTER?SocLlmContracts.Task.REPORTER_ASSIST:SocLlmContracts.Task.DEVELOPER_ASSIST,100,Duration.ofSeconds(60));}
        catch(SummaryFailure failure){assertTrue(failure.failureCode()==FailureCode.AI_BUSY||failure.failureCode()==FailureCode.AI_RATE_LIMITED);return failure;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static PostgresSocLlmAdmission admission(Map<SocLlmContracts.Role,PostgresSocLlmAdmission.KeyBudget> budgets){return admission(source,budgets);}
    private static PostgresSocLlmAdmission admission(DataSource ds,Map<SocLlmContracts.Role,PostgresSocLlmAdmission.KeyBudget> budgets){return new PostgresSocLlmAdmission(ds,budgets);}
}
