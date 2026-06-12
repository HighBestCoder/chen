package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Root;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.modules.mongodb.entity.MongoCollectionNode;
import org.jumpserver.chen.modules.mongodb.entity.MongoDatabaseNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements ResourceBrowser directly (NOT BaseResourceBrowser, whose
 * tree walk is SQL/schema-bound via getSQLActuator().getObjects). Mongo
 * tree is lazy: root(datasource) -> database -> collection(leaf). The
 * SQL-shaped introspection methods have no Mongo analogue and return empty.
 */
public class MongoResourceBrowser implements ResourceBrowser {

    private final MongoConnectionManager connectionManager;
    private final SQLHintsHandler sqlHintsHandler;
    private TreeNode root;

    public MongoResourceBrowser(MongoConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.sqlHintsHandler = new MongoSqlHintsHandlerStub();
    }

    @Override
    public void buildTree() {
        var rootNode = new Root();
        rootNode.setName(SessionManager.getCurrentSession().getDatasourceName());
        this.root = rootNode.toResourceNode(null);
    }

    @Override
    public TreeNode getTree() {
        return this.root;
    }

    @Override
    public List<TreeNode> getChildren(TreeNode node) throws SQLException {
        return getChildren(node, true);
    }

    @Override
    public List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException {
        if (node == null) {
            return List.of(this.root);
        }
        try {
            return switch (node.getType()) {
                case "datasource" -> getDatabaseNodes(node);
                case "database" -> getCollectionNodes(node);
                default -> List.of();
            };
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    private List<TreeNode> getDatabaseNodes(TreeNode parent) {
        List<TreeNode> nodes = new ArrayList<>();
        for (String dbName : this.connectionManager.listDatabases()) {
            nodes.add(new MongoDatabaseNode(dbName).toResourceNode(parent));
        }
        return nodes;
    }

    private List<TreeNode> getCollectionNodes(TreeNode parent) {
        String dbName = TreeUtils.getValue(parent.getKey(), "database");
        List<TreeNode> nodes = new ArrayList<>();
        this.connectionManager.getDatabase(dbName).listCollectionNames()
                .forEach(name -> nodes.add(new MongoCollectionNode(name).toResourceNode(parent)));
        return nodes;
    }

    @Override
    public List<Schema> getSchemas(SQL sql) {
        return List.of();
    }

    @Override
    public List<Table> getTables(SQL sql) {
        return List.of();
    }

    @Override
    public List<View> getViews(SQL sql) {
        return List.of();
    }

    @Override
    public List<Field> getFields(SQL sql) {
        return List.of();
    }

    @Override
    public SQLActuator getSQLActuator() {
        return this.connectionManager.getSqlActuator();
    }

    @Override
    public SQLHintsHandler getSQLHintsHandler() {
        return this.sqlHintsHandler;
    }
}
