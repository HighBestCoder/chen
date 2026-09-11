package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.ACLFilter;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public class ACLFilterImpl implements ACLFilter {
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;
    private final List<Common.CommandACL> commandACLs;
    private final Common.Session session;
    private final ReentrantLock reviewLock = new ReentrantLock();
    private final long timeoutMillis;
    private final long pollMillis;

    public ACLFilterImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub stub, List<Common.CommandACL> rules) {
        this(session, stub, rules, 30 * 60 * 1000L, 5000L);
    }

    ACLFilterImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub stub, List<Common.CommandACL> rules,
                  long timeoutMillis, long pollMillis) {
        this.session = session;
        this.serviceBlockingStub = stub;
        this.commandACLs = rules;
        this.timeoutMillis = timeoutMillis;
        this.pollMillis = pollMillis;
    }

    @Override
    public ACLResult commandACLFilter(String command, Connection connection) {
        return commandACLFilterBatch(command, List.of(), connection);
    }

    @Override
    public ACLResult commandACLFilterBatch(String command, List<String> statements, Connection connection) {
        var result = new ACLResult();
        Common.CommandACL acl;
        var reviewRules = new java.util.HashSet<String>();
        try {
            acl = matchRule(command, result);
            if (acl != null && acl.getAction() == Common.CommandACL.Action.Review) reviewRules.add(acl.getId());
            for (String statement : statements) {
                var candidate = new ACLResult();
                Common.CommandACL matched;
                try { matched = matchRule(statement, candidate); }
                catch (PatternSyntaxException invalid) {
                    result.setCmdAclId(candidate.getCmdAclId()); result.setCmdGroupId(candidate.getCmdGroupId()); throw invalid;
                }
                if (matched != null && matched.getAction() == Common.CommandACL.Action.Review) reviewRules.add(matched.getId());
                if (severity(matched) > severity(acl)) {
                    acl = matched;
                    result.setCmdAclId(candidate.getCmdAclId()); result.setCmdGroupId(candidate.getCmdGroupId());
                }
            }
        }
        catch (PatternSyntaxException invalid) {
            result.setRiskLevel(Common.RiskLevel.Reject);
            result.setRiskAction("invalid_rule");
            log.warn("Invalid command ACL: session={} aclId={}", session.getId(), result.getCmdAclId());
            return result;
        }
        if (acl == null) { result.setRiskLevel(Common.RiskLevel.Normal); return result; }
        switch (acl.getAction()) {
            case Accept -> { result.setRiskLevel(Common.RiskLevel.Normal); result.setRiskAction("accept"); }
            case Warning -> { result.setRiskLevel(Common.RiskLevel.Warning); result.setRiskAction("warning"); }
            case Reject -> { result.setRiskLevel(Common.RiskLevel.Reject); result.setRiskAction("reject"); }
            case Review -> {
                // A ticket belongs to one ACL/reviewer set. Never let approval
                // under one rule authorize a statement governed by another.
                if (command.codePointCount(0, command.length()) > 4090) {
                    // Core v3.10.17 stores only run_command[:4090]. Never
                    // execute a tail that was invisible to the reviewer.
                    result.setRiskLevel(Common.RiskLevel.ReviewReject);
                    result.setRiskAction("review_command_too_long");
                } else if (reviewRules.size() > 1) {
                    result.setRiskLevel(Common.RiskLevel.ReviewReject);
                    result.setRiskAction("multiple_review_rules");
                } else review(command, acl, result);
            }
            default -> { result.setRiskLevel(Common.RiskLevel.Reject); result.setRiskAction("unknown"); }
        }
        return result;
    }

    private static int severity(Common.CommandACL acl) {
        if (acl == null) return 0;
        return switch (acl.getAction()) { case Accept -> 1; case Warning -> 2; case Review -> 3; default -> 4; };
    }

    private void review(String command, Common.CommandACL acl, ACLResult result) {
        result.setRiskAction("review");
        result.setRiskLevel(Common.RiskLevel.ReviewReject);
        // One controller has one dialog. A second review must not replace the
        // first command's dialog or consume its button events.
        if (!reviewLock.tryLock()) return;
        Session owner = SessionManager.getCurrentSession();
        ServiceOuterClass.TicketInfo ticket = null;
        boolean ticketResolved = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            requireActive(owner);
            var decision = new AtomicReference<Boolean>();
            var submitted = new CountDownLatch(1);
            var confirm = new Dialog(MessageUtils.get("msg.dialog.title.command_review"));
            confirm.setBody(MessageUtils.get("msg.dialog.message.command_review"));
            confirm.addButton(new Button(MessageUtils.get("btn.label.submit"), "submit:" + UUID.randomUUID(), () -> {
                if (decision.compareAndSet(null, true)) submitted.countDown();
            }));
            confirm.addButton(new Button(MessageUtils.get("btn.label.cancel"), "cancel:" + UUID.randomUUID(), () -> {
                if (decision.compareAndSet(null, false)) submitted.countDown();
            }));
            owner.getController().showDialog(confirm);
            while (!submitted.await(Math.min(100, remaining(deadline)), TimeUnit.MILLISECONDS)) requireActive(owner);
            requireActive(owner);
            if (!Boolean.TRUE.equals(decision.get())) return;
            // Never estimate affected rows by executing the command, even in a
            // transaction. Triggers, sequences and existing transactions are not undoable that way.
            var response = boundedStub(deadline).createCommandTicket(ServiceOuterClass.CommandConfirmRequest.newBuilder()
                    .setCmd(command).setSessionId(session.getId()).setCmdAclId(acl.getId()).build());
            if (!response.getStatus().getOk()) throw new IllegalStateException("Ticket creation failed");
            ticket = response.getInfo();
            result.setTicketId(extractTicketId(ticket.getTicketDetailUrl()));
            if (result.getTicketId() == null) throw new IllegalStateException("Missing ticket identity");
            requireActive(owner);
            var outcome = new AtomicReference<Boolean>();
            var wake = new CountDownLatch(1);
            var waiting = new Dialog(MessageUtils.get("msg.dialog.title.command_review"));
            waiting.setBody(MessageUtils.get("msg.dialog.message.wait_command_review"));
            waiting.addButton(new Button(MessageUtils.get("btn.label.cancel"), "cancel:" + UUID.randomUUID(), () -> {
                if (outcome.compareAndSet(null, false)) wake.countDown();
            }));
            owner.getController().showDialog(waiting);
            while (outcome.get() == null) {
                requireActive(owner);
                var checked = boundedStub(deadline).checkTicketState(ServiceOuterClass.TicketRequest.newBuilder()
                        .setReq(ticket.getCheckReq()).build());
                requireActive(owner);
                if (!checked.getStatus().getOk()) throw new IllegalStateException("Ticket status unavailable");
                switch (checked.getData().getState()) {
                    case Approved -> { ticketResolved = true; outcome.compareAndSet(null, true); }
                    case Rejected, Closed -> { ticketResolved = true; outcome.compareAndSet(null, false); }
                    case Open -> wake.await(Math.min(pollMillis, remaining(deadline)), TimeUnit.MILLISECONDS);
                    default -> throw new IllegalStateException("Unknown ticket state");
                }
            }
            requireActive(owner);
            remaining(deadline);
            if (Boolean.TRUE.equals(outcome.get())) {
                result.setApprovedCommandHash(commandHash(command));
                result.setRiskLevel(Common.RiskLevel.ReviewAccept);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.warn("Command review denied: session={} ticket={} errorType={}",
                    session.getId(), result.getTicketId(), failure.getClass().getSimpleName());
        } finally {
            // Cancellation is cleanup only: its success must never turn a rejection into approval.
            if (ticket != null && !ticketResolved) {
                boolean interrupted = Thread.interrupted();
                try {
                    var cancelled = serviceBlockingStub.withDeadlineAfter(5, TimeUnit.SECONDS).cancelTicket(
                            ServiceOuterClass.TicketRequest.newBuilder().setReq(ticket.getCancelReq()).build());
                    if (!cancelled.getStatus().getOk()) log.warn("Ticket cleanup rejected: session={} ticket={}", session.getId(), result.getTicketId());
                }
                catch (RuntimeException failure) { log.warn("Ticket cleanup failed: session={} ticket={}", session.getId(), result.getTicketId()); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            try { if (owner != null) owner.getController().closeDialog(); }
            finally { reviewLock.unlock(); }
        }
    }

    private static void requireActive(Session owner) {
        if (owner == null || !owner.isActive() || (owner instanceof JMSSession jms && !jms.allowsCredentialRenewal()))
            throw new IllegalStateException("Session is no longer authorized");
    }

    private static long remaining(long deadline) {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new IllegalStateException("Command review timed out");
        return millis;
    }

    private ServiceGrpc.ServiceBlockingStub boundedStub(long deadline) {
        return serviceBlockingStub.withDeadlineAfter(Math.min(15000, remaining(deadline)), TimeUnit.MILLISECONDS);
    }

    private static final Pattern TICKET_ID_PATTERN = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static String extractTicketId(String url) {
        if (url == null) return null;
        var matcher = TICKET_ID_PATTERN.matcher(url);
        String last = null;
        while (matcher.find()) last = matcher.group(1);
        return last;
    }

    private Common.CommandACL matchRule(String command, ACLResult result) {
        for (var acl : commandACLs) for (var group : acl.getCommandGroupsList()) {
            result.setCmdAclId(acl.getId()); result.setCmdGroupId(group.getId());
            int flags = Pattern.UNICODE_CASE | (group.getIgnoreCase() ? Pattern.CASE_INSENSITIVE : 0);
            if (Pattern.compile(group.getPattern(), flags).matcher(command).find()) return acl;
        }
        result.setCmdAclId(null); result.setCmdGroupId(null);
        return null;
    }

    // SHA-256 of the exact command text approved in the review dialog, stored on
    // the ACLResult so the console can confirm before executing that the command
    // it is about to run is identical to the one the reviewer saw.
    // Honest boundary: chen holds the command as one immutable string for the
    // whole approve-then-execute path, so there is no in-process swap window;
    // this guards against a future refactor / caller passing a different string,
    // NOT against a frontend that replays approval against a new command (that
    // belongs to the SPA layer, Task 24).
    public static String commandHash(String command) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
