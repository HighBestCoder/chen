package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.acl.ACLResult;

import java.sql.Connection;

public interface ACLFilter {
    ACLResult commandACLFilter(String command, Connection connection);
    default ACLResult commandACLFilterBatch(String command, java.util.List<String> statements, Connection connection) {
        return commandACLFilter(command, connection);
    }
}
