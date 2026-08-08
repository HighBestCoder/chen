package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.ActionHandler;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.i18n.MessageUtils;

import java.util.List;

public class MongoActionHandler implements ActionHandler {

    @Override
    public List<Action> getActions(TreeNode node) {
        if (node == null) {
            return List.of();
        }
        return switch (node.getType()) {
            case "datasource", "database" -> List.of(refresh(), newQuery());
            case "table" -> List.of(newQuery(), preview());
            default -> List.of();
        };
    }

    @Override
    public List<Action> getDatasourceActions(TreeNode node) {
        return List.of(refresh(), newQuery());
    }

    @Override
    public List<Action> getSchemaActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getTableActions(TreeNode node) {
        return List.of(newQuery(), preview());
    }

    @Override
    public List<Action> getViewActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getFieldActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getFolderActions(TreeNode node) {
        return List.of();
    }

    @Override
    public EventEmitter doAction(TreeNode node, String action) {
        return switch (action) {
            case "new_query" -> EventEmitter.of("new_query", node.getKey());
            case "preview", "show" -> EventEmitter.of("view_data", node.getKey());
            case "refresh_node" -> EventEmitter.of("refresh_node", node.getKey());
            default -> EventEmitter.of("blank", null);
        };
    }

    @Override
    public EventEmitter handleForm(FormData formData) {
        return null;
    }

    private Action newQuery() {
        return Action.builder()
                .label(MessageUtils.get("action.new_query"))
                .key("new_query")
                .icon("el-icon-search")
                .build();
    }

    private Action preview() {
        return Action.builder()
                .label(MessageUtils.get("action.view_data"))
                .key("preview")
                .icon("el-icon-view")
                .build();
    }

    private Action refresh() {
        return Action.builder()
                .label(MessageUtils.get("action.refresh"))
                .key("refresh_node")
                .divided(true)
                .icon("el-icon-refresh")
                .build();
    }
}
