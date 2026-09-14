package org.jumpserver.chen.web.exception;

/** Only fixed, credential-free messages may be supplied here. */
public final class ConnectionRejectedException extends ChenException {
    private final int status;
    public ConnectionRejectedException(String message, int status) { super(message); this.status = status; }
    public int getStatus() { return status; }
}
