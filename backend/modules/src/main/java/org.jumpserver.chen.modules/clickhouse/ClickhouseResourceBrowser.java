package org.jumpserver.chen.modules.clickhouse;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class ClickhouseResourceBrowser extends BaseResourceBrowser {
    public ClickhouseResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new ClickhouseSQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "select schema_name as name from information_schema.schemata";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "select name from system.tables where database = ? and engine NOT LIKE '%View'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.bound(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "select name from system.tables where database = ? and engine LIKE '%View'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.bound(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT name,type FROM system.columns WHERE database = ? AND table = ? ORDER BY position";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        var fields = this.getSQLActuator().getObjects(SQL.bound(SQL_GET_FIELDS, schema, table),
                Field.class, java.util.Map.of("name", 1, "type", 2));
        for (var field : fields) {
            field.setSchema(schema);
            field.setTable(table);
            field.setNullable(field.getType().startsWith("Nullable(")
                    || field.getType().startsWith("LowCardinality(Nullable("));
            // ClickHouse sorting/primary keys do not enforce row uniqueness.
        }
        return fields;
    }



}
