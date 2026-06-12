package org.jumpserver.chen.modules.mongodb;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.springframework.web.socket.WebSocketSession;

public class MongoDatasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(MongoDatasource.class);
    }

    public MongoDatasource(DBConnectInfo dbConnectInfo) {
        var mongoConnectionManager = new MongoConnectionManager(dbConnectInfo, this);
        this.connectionManager = mongoConnectionManager;
        this.resourceBrowser = new MongoResourceBrowser(mongoConnectionManager);
        this.actionHandler = new MongoActionHandler();
    }

    @Override
    public String getName() {
        return "mongodb";
    }

    @Override
    public DbType getDruidDbType() {
        return DbType.other;
    }

    @Override
    public Console createQueryConsole(WebSocketSession ws, String nodeKey) {
        return new MongoQueryConsole(this, ws, nodeKey);
    }
}
