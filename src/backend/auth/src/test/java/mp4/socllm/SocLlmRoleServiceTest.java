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
}
