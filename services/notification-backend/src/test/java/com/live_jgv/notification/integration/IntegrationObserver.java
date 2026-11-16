package com.live_jgv.notification.integration;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.*;

/** Records sanitized outcomes after authorized setup, not raw exception messages. */
public final class IntegrationObserver implements BeforeTestExecutionCallback,AfterTestExecutionCallback,
        TestExecutionExceptionHandler,LifecycleMethodExecutionExceptionHandler {
    private IntegrationEvidence evidence;
    public void use(IntegrationEvidence evidence) { this.evidence=evidence; }
    static AssertionError sanitized(Throwable error) {
        return new AssertionError("External integration refused or failed; type="+error.getClass().getSimpleName()+"; sensitive details withheld");
    }
    @Override public void handleTestExecutionException(ExtensionContext context,Throwable error) throws Throwable {
        if(evidence!=null) evidence.record(context.getRequiredTestMethod().getName(),Map.of("result","FAILED","error_type",error.getClass().getSimpleName()));
        throw sanitized(error);
    }
    @Override public void handleBeforeAllMethodExecutionException(ExtensionContext context,Throwable error) { throw sanitized(error); }
    @Override public void handleAfterAllMethodExecutionException(ExtensionContext context,Throwable error) { throw sanitized(error); }
    @Override public void beforeTestExecution(ExtensionContext context) {
        context.getStore(ExtensionContext.Namespace.create(getClass())).put(context.getUniqueId(),System.nanoTime());
    }
    @Override public void afterTestExecution(ExtensionContext context) throws Exception {
        Long start=context.getStore(ExtensionContext.Namespace.create(getClass())).remove(context.getUniqueId(),Long.class);
        if(evidence==null || start==null) return;
        var error=context.getExecutionException();
        evidence.record(context.getRequiredTestMethod().getName(),Map.of("result",error.isPresent() ? "FAILED" : "PASSED",
                "error_type",error.map(value->value.getClass().getSimpleName()).orElse("NONE"),
                "elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)));
    }
}
