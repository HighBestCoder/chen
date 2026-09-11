import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
public class TestTreeKeyBoundaries {
    public static void main(String[] args){
        TreeNode root=new TreeNode();root.setKey(TreeUtils.generateNodeKey(null,"database","one,two:three%2C"));
        TreeNode schema=new TreeNode();schema.setKey(TreeUtils.generateNodeKey(root,"schema","A:B"));
        String table=TreeUtils.generateNodeKey(schema,"table","中文+x,y:z%25");
        if(!"one,two:three%2C".equals(TreeUtils.getValue(table,"database"))
                || !"A:B".equals(TreeUtils.getValue(table,"schema"))
                || !"中文+x,y:z%25".equals(TreeUtils.getValue(table,"table"))
                || !schema.getKey().equals(TreeUtils.getParentKey(table)))throw new AssertionError("tree key changed selected database/schema/table");
        if(!"".equals(TreeUtils.getValue("schema:","schema")))throw new AssertionError("empty value failed");
        System.out.println("OK: tree key reserved characters round-trip without changing parent or selected object");
    }
}
