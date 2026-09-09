import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.wisp.Common;
import java.util.List;

/** Rule priority, casing and batch boundaries; no DB or Core required. */
public class TestACLRuleBoundaries {
    static int failures;
    static void check(String label, String command, String pattern, boolean ignoreCase, boolean rejected) {
        var acl=Common.CommandACL.newBuilder().setId("fixture-acl").setAction(Common.CommandACL.Action.Reject)
                .addCommandGroups(Common.CommandGroup.newBuilder().setId("fixture-group").setPattern(pattern).setIgnoreCase(ignoreCase)).build();
        var result=new ACLFilterImpl(Common.Session.getDefaultInstance(),null,List.of(acl)).commandACLFilter(command,null);
        boolean actual=result.getRiskLevel()==Common.RiskLevel.Reject;
        if(actual!=rejected){failures++;System.out.println("FAIL "+label);}else System.out.println("PASS "+label);
    }
    public static void main(String[] args) {
        check("case-sensitive uppercase rule", "DROP TABLE fixture", "^DROP", false, true);
        check("case-sensitive lowercase rule must not match uppercase", "DROP TABLE fixture", "^drop", false, false);
        check("case-insensitive rule", "DrOp TABLE fixture", "^DROP", true, true);
        check("invalid rule must fail closed", "DELETE FROM fixture", "[", false, true);
        var unknown=Common.CommandACL.newBuilder().setActionValue(99)
                .addCommandGroups(Common.CommandGroup.newBuilder().setPattern(".*")).build();
        if(new ACLFilterImpl(Common.Session.getDefaultInstance(),null,List.of(unknown)).commandACLFilter("SELECT 1",null).getRiskLevel()!=Common.RiskLevel.Reject){failures++;System.out.println("FAIL unknown action");}
        var allow=Common.CommandACL.newBuilder().setId("allow-select").setAction(Common.CommandACL.Action.Accept)
                .addCommandGroups(Common.CommandGroup.newBuilder().setPattern("^SELECT").setIgnoreCase(true)).build();
        var deny=Common.CommandACL.newBuilder().setId("deny-drop").setAction(Common.CommandACL.Action.Reject)
                .addCommandGroups(Common.CommandGroup.newBuilder().setPattern("^DROP").setIgnoreCase(true)).build();
        var batch=new ACLFilterImpl(Common.Session.getDefaultInstance(),null,List.of(allow,deny))
                .commandACLFilterBatch("SELECT 1; DROP TABLE fixture",List.of("SELECT 1","DROP TABLE fixture"),null);
        if(batch.getRiskLevel()!=Common.RiskLevel.Reject || !"deny-drop".equals(batch.getCmdAclId())){failures++;System.out.println("FAIL multi-statement priority");}
        var reviewSelect=allow.toBuilder().setAction(Common.CommandACL.Action.Review).build();
        var reviewDrop=deny.toBuilder().setAction(Common.CommandACL.Action.Review).build();
        var mixed=new ACLFilterImpl(Common.Session.getDefaultInstance(),null,List.of(reviewSelect,reviewDrop))
                .commandACLFilterBatch("SELECT 1; DROP TABLE fixture",List.of("SELECT 1","DROP TABLE fixture"),null);
        if(mixed.getRiskLevel()!=Common.RiskLevel.ReviewReject || !"multiple_review_rules".equals(mixed.getRiskAction())){
            failures++;System.out.println("FAIL one ticket accepted distinct reviewer sets");
        }
        String longCommand="SELECT "+"x".repeat(4090);
        var tooLong=new ACLFilterImpl(Common.Session.getDefaultInstance(),null,List.of(reviewSelect)).commandACLFilter(longCommand,null);
        if(tooLong.getRiskLevel()!=Common.RiskLevel.ReviewReject || !"review_command_too_long".equals(tooLong.getRiskAction())){
            failures++;System.out.println("FAIL Core truncated review command accepted");
        }
        if(failures>0)throw new AssertionError(failures+" ACL rule boundary failures");
        System.out.println("OK: ACL rule boundaries");
    }
}
