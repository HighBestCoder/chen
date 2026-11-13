import org.jumpserver.chen.framework.jms.impl.CommandHandlerImpl;
import org.jumpserver.chen.wisp.Common;

import java.util.Map;

/**
 * The outbox delivers risk_level straight into Core's terminal_command, so it
 * must carry Core RiskLevelChoices values, never wisp proto numbers.
 */
public class TestCommandRiskLevelMapping {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        Map<Common.RiskLevel, Integer> expected = Map.of(
                Common.RiskLevel.Normal, 0,
                Common.RiskLevel.Warning, 4,
                Common.RiskLevel.Reject, 5,
                Common.RiskLevel.ReviewReject, 6,
                Common.RiskLevel.ReviewAccept, 7,
                Common.RiskLevel.ReviewCancel, 8);
        expected.forEach((level, core) -> check(CommandHandlerImpl.coreRiskLevel(level) == core,
                level + " must map to Core " + core + ", got " + CommandHandlerImpl.coreRiskLevel(level)));
        check(CommandHandlerImpl.coreRiskLevel(Common.RiskLevel.UNRECOGNIZED) == 5, "UNRECOGNIZED must audit as reject");
        check(CommandHandlerImpl.coreRiskLevel(null) == 0, "missing level must audit as accept");
        // Every enum constant is covered, so a new proto level cannot slip through unmapped.
        for (Common.RiskLevel level : Common.RiskLevel.values())
            check(level == Common.RiskLevel.UNRECOGNIZED || expected.containsKey(level), "unmapped level " + level);
        System.out.println("TestCommandRiskLevelMapping OK");
    }
}
