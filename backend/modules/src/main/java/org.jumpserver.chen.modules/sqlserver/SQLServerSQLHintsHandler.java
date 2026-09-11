package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLHintsHandler;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SQLServerSQLHintsHandler extends BaseSQLHintsHandler {

    private final ConnectionManager connectionManager;


    public SQLServerSQLHintsHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }


    public List<Table> getALlTables(String schema) throws SQLException {
        return null;
    }

    public List<Field> getAllFields(String schema) throws SQLException {
        return null;
    }

    private static final String GET_ALL_SCHEMAS = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA";

    public List<Schema> getAllSchemas() throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(GET_ALL_SCHEMAS).getSql(),
                        Schema.class, Map.of("name", 1));
    }

    private static final String GET_ALL_TABLES = "SELECT TABLE_NAME AS NAME,TABLE_SCHEMA FROM INFORMATION_SCHEMA.TABLES";

    public List<Table> getAllTables() throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(GET_ALL_TABLES).getSql(),
                        Table.class, Map.of("name", 1, "schema", 2));
    }

    private static final String GET_ALL_FIELDS = "SELECT COLUMN_NAME AS NAME, TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS";

    public List<Field> getAllFields() throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(GET_ALL_FIELDS).getSql(),
                        Field.class, Map.of("name", 1, "schema", 2, "table", 3));
    }

    @Override
    public Map<String, List<String>> getHints(String nodeKey, String context) throws SQLException {
        Map<String, List<String>> suggestions = new HashMap<>();
        if (!org.jumpserver.chen.framework.session.SessionManager.getCurrentSession().enableAutoComplete()) return suggestions;

        var schemas = this.getAllSchemas();
        var schemaNames = schemas.stream().map(Schema::getName).toList();

        var tables = this.getAllTables();
        var fields = this.getAllFields();

        schemaNames.forEach(schemaName -> {
            var tableNames = tables.stream().filter(table -> table.getSchema().equals(schemaName)).map(Table::getName).toList();
            suggestions.put(schemaName, tableNames);
        });

        tables.forEach(table -> {
            var fieldNames = fields.stream().filter(field -> field.getTable().equals(table.getName())
                    && field.getSchema().equals(table.getSchema())).map(Field::getName).toList();
            suggestions.put(table.getSchema() + "." + table.getName(), fieldNames);
            // Unqualified names are only unambiguous for a unique table name.
            if (tables.stream().filter(other -> other.getName().equals(table.getName())).count() == 1) {
                suggestions.put(table.getName(), fieldNames);
            }
        });

        return suggestions;
    }


}
