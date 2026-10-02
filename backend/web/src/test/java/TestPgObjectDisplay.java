import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.postgresql.util.PGobject;

/** DEF-32: PostgreSQL inet/json/interval values are shown as text, not [object Object]. */
public class TestPgObjectDisplay {
    static class PGInterval extends PGobject { }

    public static void main(String[] args) {
        PGobject inet = new PGobject(); inet.setValue("10.1.2.3");
        PGInterval interval = new PGInterval(); interval.setValue("1 day");
        require("10.1.2.3".equals(BaseSQLActuator.displayValue(inet)), "inet not shown as text");
        require("1 day".equals(BaseSQLActuator.displayValue(interval)), "PGobject subclass not shown as text");
        require(BaseSQLActuator.displayValue(null) == null, "null changed");
        require("42".equals(BaseSQLActuator.displayValue(42L)), "long rendering changed");
        Object other = new Object();
        require(BaseSQLActuator.displayValue(other) == other, "unrelated object changed");
        System.out.println("OK: PostgreSQL extension types render as text");
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
