import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.springframework.context.support.ResourceBundleMessageSource;

public class TestEnglishErrorMessages {
    public static void main(String[] args) {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/chen"); source.setDefaultEncoding("UTF-8");
        new MessageUtils(source);
        var locale = new AtomicReference<>(Locale.CHINA);
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class},
                (o, m, a) -> m.getName().equals("getLocale") ? locale.get() : null);
        String token = SessionManager.registerSession(session); SessionManager.setContext(token);
        try {
            for (Locale language : new Locale[]{Locale.CHINA, Locale.TAIWAN, Locale.JAPAN, Locale.US}) {
                locale.set(language);
                for (String key : ResourceBundle.getBundle("i18n/chen", language).keySet()) {
                    if (!(key.startsWith("msg.error.") || key.endsWith("init_datasource_failed")
                            || key.equals("msg.dialog.title.error_message") || key.equals("msg.dialog.session_locked")
                            || key.equals("msg.dialog.session_unlocked"))) continue;
                    String actual = MessageUtils.get(key, 5, 5);
                    String expected = String.format(Locale.US, source.getMessage(key, null, Locale.US), 5, 5);
                    if (!actual.equals(expected) || actual.matches(".*\\p{IsHan}.*") || actual.equals(key))
                        throw new AssertionError(language + " " + key + ": " + actual);
                }
            }
            locale.set(Locale.CHINA);
            if (!MessageUtils.get("action.refresh").equals("刷新")) throw new AssertionError("UI language changed");
            if (!MessageUtils.get("msg.error.acl_reject").equals("Command execution blocked by access control policy."))
                throw new AssertionError("ACL denial not English");
            if (!MessageUtils.get("msg.error.command_review_reject", "审批员").contains("审批员"))
                throw new AssertionError("User-provided name changed");
        } finally { SessionManager.unregisterSession(token); SessionManager.setContext(null); }
        if (!MessageUtils.get("msg.error.no_permission").startsWith("You do not have permission"))
            throw new AssertionError("Errors before session initialization must also be English");
        System.out.println("PASS English errors across four locales, UI locale preserved, placeholders and no-session errors");
    }
}
