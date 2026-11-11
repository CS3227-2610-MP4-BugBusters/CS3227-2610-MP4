package mp4.socllm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mp4.maintainer.summary.SummaryContracts.*;
import org.junit.jupiter.api.Test;

class SocLlmRoleServiceTest {
    @Test void roleAndTaskMustMatchBeforeAdmissionOrProviderCall() {
        AtomicInteger admissions=new AtomicInteger();
        SocLlmContracts.RoleAdmission admission=(role,actor,task,tokens,lease)->{admissions.incrementAndGet();throw new AssertionError("unauthorized role must be rejected first");};
        try(var reporter=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,"placeholder","test-model",admission,Clock.systemUTC())) {
            SummaryFailure wrongActor=assertThrows(SummaryFailure.class,()->reporter.generate(SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,"rules","data",100,Duration.ofSeconds(2)));
            SummaryFailure wrongTask=assertThrows(SummaryFailure.class,()->reporter.generate(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,"rules","data",100,Duration.ofSeconds(2)));
            assertEquals(FailureCode.AI_UNAVAILABLE,wrongActor.failureCode());
            assertEquals(FailureCode.AI_UNAVAILABLE,wrongTask.failureCode());
            assertEquals(0,admissions.get());
        }
    }

    @Test void failedAdmissionCannotStartAnyProviderWork() {
        AtomicInteger providerCalls=new AtomicInteger();
        SocLlmContracts.RoleAdmission unavailable=(role,actor,task,tokens,lease)->{throw new SummaryFailure(FailureCode.AI_UNAVAILABLE);};
        try(var developer=new SocLlmRoleService(SocLlmContracts.Role.DEVELOPER,unavailable,
                (instructions,input,tokens,deadline)->{providerCalls.incrementAndGet();return "unexpected";})) {
            assertEquals(FailureCode.AI_UNAVAILABLE,assertThrows(SummaryFailure.class,()->developer.generate(
                    SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,
                    "rules","input",100,Duration.ofSeconds(2))).failureCode());
            assertEquals(0,providerCalls.get());
        }
    }

    private static SocLlmTaskPolicies policies(SocLlmTaskPolicy reporter, SocLlmTaskPolicy developer) {
        var map = new java.util.EnumMap<SocLlmContracts.Task, SocLlmTaskPolicy>(SocLlmContracts.Task.class);
        map.put(SocLlmContracts.Task.REPORTER_ASSIST, reporter);
        map.put(SocLlmContracts.Task.DEVELOPER_ASSIST, developer);
        map.put(SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY, SocLlmTaskPolicies.defaults().forTask(SocLlmContracts.Task.MAINTAINER_QUEUE_SUMMARY));
        return SocLlmTaskPolicies.of(map);
    }

    private static SocLlmTaskPolicy small() {
        return new SocLlmTaskPolicy(100, 50, 20, Duration.ofSeconds(5), 3, Duration.ofMinutes(5), 1);
    }

    private static SocLlmTaskPolicy big() {
        return new SocLlmTaskPolicy(1_000, 500, 200, Duration.ofSeconds(20), 3, Duration.ofMinutes(5), 1);
    }

    @Test void TL04_inputOneByteOverTaskLimitRejectedWithoutAdmissionOrProvider() {
        AtomicInteger admissions=new AtomicInteger(), providers=new AtomicInteger();
        SocLlmContracts.RoleAdmission admission=(role,actor,task,tokens,lease)->{admissions.incrementAndGet();throw new AssertionError("must not admit");};
        try(var svc=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,
                (i,in,t,d)->{providers.incrementAndGet();return "x";},policies(small(),big()))) {
            SummaryFailure f=assertThrows(SummaryFailure.class,()->svc.generate(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),
                    SocLlmContracts.Task.REPORTER_ASSIST,"rules","a".repeat(101),10,Duration.ofSeconds(2)));
            assertEquals(FailureCode.AI_UNAVAILABLE,f.failureCode());
            assertFalse(String.valueOf(f.getMessage()).contains("aaaa"));
            assertEquals(0,admissions.get());
            assertEquals(0,providers.get());
        }
    }

    @Test void TL04_instructionOutputAndDeadlineOverTaskLimitRejected() {
        AtomicInteger admissions=new AtomicInteger();
        SocLlmContracts.RoleAdmission admission=(role,actor,task,tokens,lease)->{admissions.incrementAndGet();throw new AssertionError("must not admit");};
        try(var svc=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,(i,in,t,d)->"x",policies(small(),big()))) {
            UUID a=UUID.randomUUID(); var R=SocLlmContracts.Role.REPORTER; var T=SocLlmContracts.Task.REPORTER_ASSIST;
            assertThrows(SummaryFailure.class,()->svc.generate(R,a,T,"i".repeat(51),"data",10,Duration.ofSeconds(2)));
            assertThrows(SummaryFailure.class,()->svc.generate(R,a,T,"rules","data",21,Duration.ofSeconds(2)));
            assertThrows(SummaryFailure.class,()->svc.generate(R,a,T,"rules","data",10,Duration.ofSeconds(6)));
            assertEquals(0,admissions.get());
        }
    }

    @Test void TL05_callExactlyAtTaskLimitsIsAdmittedWithReservation() {
        AtomicInteger reserved=new AtomicInteger(-1);
        SocLlmContracts.RoleAdmission admission=(role,actor,task,tokens,lease)->{reserved.set(tokens);return mock(AdmissionLease.class);};
        try(var svc=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,(i,in,t,d)->"ok",policies(small(),big()))) {
            assertEquals("ok",svc.generate(SocLlmContracts.Role.REPORTER,UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,
                    "i".repeat(50),"a".repeat(100),20,Duration.ofSeconds(5)));
            assertEquals(170,reserved.get());
        }
    }

    @Test void TL06_callFittingAnotherTasksPolicyButNotItsOwnIsRejected() {
        AtomicInteger admissions=new AtomicInteger();
        SocLlmContracts.RoleAdmission admission=(role,actor,task,tokens,lease)->{admissions.incrementAndGet();return mock(AdmissionLease.class);};
        // 500 input bytes fits DEVELOPER_ASSIST's policy (1000) but not REPORTER_ASSIST's (100).
        try(var svc=new SocLlmRoleService(SocLlmContracts.Role.REPORTER,admission,(i,in,t,d)->"ok",policies(small(),big()))) {
            assertEquals(FailureCode.AI_UNAVAILABLE,assertThrows(SummaryFailure.class,()->svc.generate(SocLlmContracts.Role.REPORTER,
                    UUID.randomUUID(),SocLlmContracts.Task.REPORTER_ASSIST,"rules","a".repeat(500),10,Duration.ofSeconds(2))).failureCode());
            assertEquals(0,admissions.get());
        }
        // Reverse: developer service with the larger policy accepts what the reporter policy would refuse.
        try(var dev=new SocLlmRoleService(SocLlmContracts.Role.DEVELOPER,admission,(i,in,t,d)->"ok",policies(small(),big()))) {
            assertEquals("ok",dev.generate(SocLlmContracts.Role.DEVELOPER,UUID.randomUUID(),SocLlmContracts.Task.DEVELOPER_ASSIST,
                    "rules","a".repeat(500),10,Duration.ofSeconds(2)));
        }
    }
}
