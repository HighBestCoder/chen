package org.jumpserver.chen.framework.jms.acl;

import lombok.Data;
import org.jumpserver.chen.wisp.Common;

@Data
public class ACLResult {
    public boolean allows(String command) {
        if (riskLevel == Common.RiskLevel.Reject || riskLevel == Common.RiskLevel.ReviewReject
                || riskLevel == Common.RiskLevel.ReviewCancel || riskLevel == Common.RiskLevel.UNRECOGNIZED) return false;
        if (riskLevel == Common.RiskLevel.ReviewAccept && approvedCommandHash == null) return false;
        return approvedCommandHash == null || approvedCommandHash.equals(
                org.jumpserver.chen.framework.jms.impl.ACLFilterImpl.commandHash(command));
    }

    public String denialMessage() {
        String key = switch (riskAction == null ? "" : riskAction) {
            case "review_command_too_long" -> "msg.error.review_command_too_long";
            case "multiple_review_rules" -> "msg.error.multiple_review_rules";
            case "session_unavailable" -> "msg.error.session_unavailable";
            default -> "msg.error.acl_reject";
        };
        return org.jumpserver.chen.framework.i18n.MessageUtils.get(key);
    }

    private Common.RiskLevel riskLevel;

    private String CmdAclId;

    private String CmdGroupId;

    private String riskAction;

    private String ticketId;

    private String approvedCommandHash;

}
