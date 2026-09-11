package org.jumpserver.chen.framework.datasource.error;

import org.jumpserver.chen.framework.i18n.MessageUtils;

/** Typed authorization denial across native driver and console boundaries. */
public final class OperationPermissionDeniedException extends RuntimeException {
    public OperationPermissionDeniedException(Throwable cause) {
        super(MessageUtils.get("msg.error.no_operation_permission"), cause);
    }
}
