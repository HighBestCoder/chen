package org.jumpserver.chen.modules.mongodb.entity;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNode;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class MongoDatabaseNode implements ResourceNode {
    private String name;

    public MongoDatabaseNode(String name) {
        this.name = name;
    }

    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setType("database");
        treeNode.setLabel(this.name);
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), this.name));
        return treeNode;
    }
}
