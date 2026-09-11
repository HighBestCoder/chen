import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.*;
import org.springframework.context.support.ResourceBundleMessageSource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class TestS09Messages {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception {
        Set<String> keys=new HashSet<>();
        try(var paths=Files.walk(Path.of("backend"))){
            for(Path path:paths.filter(p->p.toString().contains("/src/main/") && p.toString().endsWith(".java") && !p.getFileName().toString().startsWith("._")).toList()){
                var matcher=Pattern.compile("MessageUtils.get\\(\"([^\"]+)\"").matcher(Files.readString(path));
                while(matcher.find())keys.add(matcher.group(1));
            }
        }
        check(keys.size()>30,"source key inventory empty");
        var source=new ResourceBundleMessageSource();source.setBasename("i18n/chen");source.setDefaultEncoding("UTF-8");source.setFallbackToSystemLocale(false);
        new MessageUtils(source);
        for(String name:List.of("chen","chen_en_US","chen_zh_CN","chen_zh_HANT","chen_ja_JP")){
            Properties properties=new Properties();
            try(var reader=Files.newBufferedReader(Path.of("backend/web/src/main/resources/i18n/"+name+".properties"))){properties.load(reader);}
            for(String key:keys)check(properties.getProperty(key)!=null && !properties.getProperty(key).isBlank(),name+" missing/empty "+key);
        }
        Locale[] locale={Locale.US};
        Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(p,m,a)->m.getName().equals("getLocale")?locale[0]:null);
        String token=SessionManager.registerSession(session);SessionManager.setContext(token);
        try{
            check("Error while reading file".equals(MessageUtils.get("msg.error.file_read_error")),"English file error");
            locale[0]=Locale.SIMPLIFIED_CHINESE;check("获取数据失败".equals(MessageUtils.get("msg.error.fetch_error")),"Chinese fetch error");
            locale[0]=Locale.FRANCE;check("Query".equals(MessageUtils.get("title.query")),"unsupported locale fallback");
        }finally{SessionManager.unregisterSession(token);SessionManager.setContext(null);}
        System.out.println("OK: all backend literal message keys, five bundles, session locale and fallback");
    }
}
