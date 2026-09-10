package org.jumpserver.chen.framework.audit;

import org.springframework.stereotype.Component;

/** Recover pending audit events at startup, even if no user reconnects. */
@Component
public class AuditOutboxStartup {
    public AuditOutboxStartup() { AuditOutbox.configured(); }
}
