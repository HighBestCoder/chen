package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.ActionHandler;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;

import java.util.List;

/**
 * P1 placeholder: returns no context-menu actions. Real Mongo actions
 * (new query / view documents / refresh) are a later phase.
 */
public class MongoActionHandler implements ActionHandler {

    @Override
    public List<Action> getActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getDatasourceActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getSchemaActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getTableActions(TreeNode node) {
        return List.of();
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
        return null;
    }

    @Override
    public EventEmitter handleForm(FormData formData) {
        return null;
    }
}
