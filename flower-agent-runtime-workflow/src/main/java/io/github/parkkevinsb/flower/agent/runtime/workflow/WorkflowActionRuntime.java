package io.github.parkkevinsb.flower.agent.runtime.workflow;

import io.github.parkkevinsb.flower.agent.runtime.DefaultActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.pipeline.ActionExecutionSession;
import io.github.parkkevinsb.flower.agent.runtime.validation.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.pipeline.ActionPipeline;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.action.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.ActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.approval.ApprovalGate;
import io.github.parkkevinsb.flower.agent.runtime.audit.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.policy.DefaultPolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.duplicate.DuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.policy.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.run.RunStore;
import io.github.parkkevinsb.flower.agent.runtime.audit.TraceSink;
import io.github.parkkevinsb.flower.core.event.EventBus;
import io.github.parkkevinsb.flower.core.event.InMemoryEventBus;
import io.github.parkkevinsb.flower.core.flow.Flow;
import io.github.parkkevinsb.flower.core.flow.FlowState;
import io.github.parkkevinsb.flower.core.time.Clock;
import io.github.parkkevinsb.flower.core.time.SystemClock;

import java.util.Objects;

/**
 * Workflow backend for the controlled action runtime.
 *
 * <p>It runs the same {@link ActionPipeline} stages as
 * {@link io.github.parkkevinsb.flower.agent.runtime.DefaultActionRuntime}, but each stage becomes a Flow Step so
 * the execution can be inspected through Flower's Flow/Step model. Governance behavior stays identical because both
 * backends share the one stage definition.</p>
 */
public final class WorkflowActionRuntime implements ActionRuntime {
    public static final String FLOW_TYPE = "flower-agent-runtime-action";
    private static final int DEFAULT_MAX_SYNC_TICKS = 64;

    private final ActionRegistry registry;
    private final ActionInputValidator inputValidator;
    private final PolicyGate policyGate;
    private final ApprovalGate approvalGate;
    private final DuplicateActionPolicy duplicateActionPolicy;
    private final AuditSink auditSink;
    private final TraceSink traceSink;
    private final RunStore runStore;
    private final Clock clock;
    private final EventBus eventBus;
    private final int maxSyncTicks;

    public WorkflowActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            Clock clock,
            EventBus eventBus,
            int maxSyncTicks) {
        this(registry, inputValidator, policyGate, approvalGate, duplicateActionPolicy, auditSink, traceSink,
                clock, eventBus, maxSyncTicks, RunStore.noop());
    }

    public WorkflowActionRuntime(
            ActionRegistry registry,
            ActionInputValidator inputValidator,
            PolicyGate policyGate,
            ApprovalGate approvalGate,
            DuplicateActionPolicy duplicateActionPolicy,
            AuditSink auditSink,
            TraceSink traceSink,
            Clock clock,
            EventBus eventBus,
            int maxSyncTicks,
            RunStore runStore) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.inputValidator = inputValidator == null ? ActionInputValidator.allowAll() : inputValidator;
        this.policyGate = policyGate == null ? new DefaultPolicyGate() : policyGate;
        this.approvalGate = approvalGate == null ? ApprovalGate.unsupported() : approvalGate;
        this.duplicateActionPolicy = duplicateActionPolicy == null
                ? DuplicateActionPolicy.acceptAll()
                : duplicateActionPolicy;
        this.auditSink = auditSink == null ? AuditSink.noop() : auditSink;
        this.traceSink = traceSink == null ? TraceSink.noop() : traceSink;
        this.runStore = runStore == null ? RunStore.noop() : runStore;
        this.clock = clock == null ? SystemClock.INSTANCE : clock;
        this.eventBus = eventBus == null ? InMemoryEventBus.create() : eventBus;
        this.maxSyncTicks = maxSyncTicks <= 0 ? DEFAULT_MAX_SYNC_TICKS : maxSyncTicks;
    }

    public WorkflowActionRuntime(ActionRegistry registry) {
        this(registry, null, null, null, null, null, null, null, null, DEFAULT_MAX_SYNC_TICKS);
    }

    @Override
    public ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(context, "context must not be null");

        ActionExecutionSession session = newSession(proposal, context);
        Flow flow = createFlow(session);
        flow.attach(clock, eventBus);
        int ticks = 0;
        while (!flow.state().isTerminal() && ticks++ < maxSyncTicks) {
            flow.tick();
        }
        if (flow.state() == FlowState.FAILED) {
            Throwable cause = flow.failureCause();
            if (cause instanceof Error error) {
                throw error;
            }
            ActionPipeline.failRuntime(session, cause);
        } else if (!flow.state().isTerminal()) {
            flow.cancel();
            ActionPipeline.failRuntime(session, new IllegalStateException(
                    "Flow action runtime did not terminate within sync tick budget."));
        }
        return session.result();
    }

    public ActionExecutionSession newSession(ActionProposal proposal, ExecutionContext context) {
        return new ActionExecutionSession(
                proposal,
                context,
                registry,
                inputValidator,
                policyGate,
                approvalGate,
                duplicateActionPolicy,
                auditSink,
                traceSink,
                runStore);
    }

    public Flow createFlow(ActionExecutionSession session) {
        Objects.requireNonNull(session, "session must not be null");
        ActionPipeline.NamedStage finalizeStage = ActionPipeline.finalizeStage();
        var builder = Flow.builder(
                FLOW_TYPE,
                session.proposal().proposalId() + ":" + session.proposal().actionId());
        for (ActionPipeline.NamedStage gate : ActionPipeline.gates()) {
            builder = builder.step(gate.name(), new StageStep(session, gate.stage(), finalizeStage.name()));
        }
        builder = builder.step(finalizeStage.name(), new FinalizeStep(session, finalizeStage.stage()));
        return builder.executionContext(toFlowerExecutionContext(session.context())).build();
    }

    private static io.github.parkkevinsb.flower.core.context.ExecutionContext toFlowerExecutionContext(
            ExecutionContext context) {
        return io.github.parkkevinsb.flower.core.context.ExecutionContext.builder()
                .tenantId(emptyToNull(context.tenantId()))
                .userId(emptyToNull(context.userId()))
                .runId(emptyToNull(context.runId()))
                .traceId(emptyToNull(context.traceId()))
                .correlationId(emptyToNull(context.runId()))
                .build();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
