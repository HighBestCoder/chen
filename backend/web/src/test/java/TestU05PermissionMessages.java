import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.datasource.error.*;
import org.jumpserver.chen.modules.mongodb.MongoPermissionErrorClassifier;
import org.jumpserver.chen.web.interceptor.WebExceptionResolver;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.*;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Locale;

public class TestU05PermissionMessages {
    static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception {
        var source=new ResourceBundleMessageSource();source.setBasename("i18n/chen");source.setDefaultEncoding("UTF-8");new MessageUtils(source);
        var ds=(org.jumpserver.chen.framework.datasource.Datasource)Proxy.newProxyInstance(
                org.jumpserver.chen.framework.datasource.Datasource.class.getClassLoader(),new Class[]{org.jumpserver.chen.framework.datasource.Datasource.class},
                (o,m,a)->{if(m.getName().equals("getChildren"))throw new SQLException("denied root metadata","42501");return null;});
        Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(o,m,a)->m.getName().equals("getLocale")?Locale.SIMPLIFIED_CHINESE:m.getName().equals("getDatasource")?ds:null);
        String token=SessionManager.registerSession(session);SessionManager.setContext(token);
        try {
            try {new org.jumpserver.chen.web.service.ResourceService().getChildren(null,false);throw new AssertionError("root denial lost");}
            catch (RuntimeException error) {require(SqlPermissionErrorClassifier.isPermissionDenied(error),"root metadata denial masked by null node");}
            SQLException nested=new SQLException("driver detail");nested.setNextException(new SQLException("private table","42501"));
            var wrapped=new RuntimeException("wrapper",nested);
            for(Throwable error:new Throwable[]{nested,wrapped,new OperationPermissionDeniedException(new com.mongodb.MongoException(13,"private namespace"))}) {
                Message message=Message.error("Fetch error",error);
                require(message.getTitle().equals("无该操作权限")&&message.getMessage().equals("无该操作权限"),"console permission message leaked detail or lost translation");
                MockHttpServletResponse response=new MockHttpServletResponse();new WebExceptionResolver().resolveException(new MockHttpServletRequest(),response,null,new RuntimeException(error));
                require(response.getStatus()==403&&response.getContentAsString().equals("无该操作权限"),"HTTP permission response incorrect");
            }
            MockHttpServletResponse rawMongo=new MockHttpServletResponse();
            new WebExceptionResolver().resolveException(new MockHttpServletRequest(),rawMongo,null,new com.mongodb.MongoException(13,"private namespace"));
            require(rawMongo.getStatus()==403&&rawMongo.getContentAsString().equals("无该操作权限"),"native Mongo metadata HTTP denial not normalized");
            MockHttpServletResponse auth=new MockHttpServletResponse();
            new WebExceptionResolver().resolveException(new MockHttpServletRequest(),auth,null,new com.mongodb.MongoException(18,"bad authentication"));
            require(auth.getStatus()==500&&!auth.getContentAsString().contains("无该操作权限"),"HTTP authentication mislabeled authorization");
            require(MongoPermissionErrorClassifier.isPermissionDenied(new RuntimeException(new com.mongodb.MongoException(13,"Unauthorized"))),"Mongo wrapped denial lost");
            require(!MongoPermissionErrorClassifier.isPermissionDenied(new com.mongodb.MongoException(18,"AuthenticationFailed")),"Mongo authentication mislabeled");
            require(!MongoPermissionErrorClassifier.isPermissionDenied(new com.mongodb.MongoException(11000,"duplicate key")),"constraint mislabeled");
            SQLException a=new SQLException("a"),b=new SQLException("b");a.initCause(b);b.initCause(a);
            require(!SqlPermissionErrorClassifier.isPermissionDenied(a),"cyclic non-permission exception classified");
            require(Message.error("Execute error",new SQLException("syntax","42601")).getTitle().equals("Execute error"),"syntax error relabeled");
        } finally {SessionManager.unregisterSession(token);SessionManager.setContext(null);}
        System.out.println("U05 localized console/HTTP permission denial, cause cycles and auth/constraint/syntax negatives passed");
    }
}
