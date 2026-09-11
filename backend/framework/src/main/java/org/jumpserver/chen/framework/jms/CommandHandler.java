package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.entity.CommandRecord;

public interface CommandHandler {
    default void beginCommand(CommandRecord record) { }

    void recordCommand(CommandRecord commandRecord);

}
