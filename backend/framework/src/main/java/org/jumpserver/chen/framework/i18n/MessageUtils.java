package org.jumpserver.chen.framework.i18n;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.session.SessionManager;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MessageUtils {

    private static MessageSource messageSource;

    public MessageUtils(MessageSource messageSource) {
        MessageUtils.messageSource = messageSource;
    }

    public static String get(String msgKey, Object... args) {
        try {
            // Customer-facing errors must remain English regardless of the UI language.
            boolean error = msgKey.startsWith("msg.error.") || msgKey.endsWith("init_datasource_failed")
                    || msgKey.equals("msg.dialog.title.error_message") || msgKey.equals("msg.dialog.session_locked")
                    // The unlock notice pairs with the lock notice; it was shown in the UI language while the
                    // lock notice was English (RUN-261002 OBS-07).
                    || msgKey.equals("msg.dialog.session_unlocked");
            var locale = error ? java.util.Locale.US : SessionManager.getCurrentSession().getLocale();
            var text = messageSource.getMessage(msgKey, null, locale);
            return String.format(locale, text, args);
        } catch (Exception e) {
            log.warn("Message not found: {}", msgKey);
            return msgKey;
        }
    }
}