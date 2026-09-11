import com.mongodb.MongoClientSettings;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;

public class TestU08MongoSettings {
    static MongoClientSettings settings(String host,boolean oidc,boolean proxy)throws Exception {
        DBConnectInfo info=new DBConnectInfo();info.setHost(host);info.setPort(27017);info.setDb("fixture");info.setUser("fixture");info.setPassword("fixture-token");
        if(oidc)info.getOptions().put("relationalAuthDecision","V2_OIDC_TOKEN_REQUIRED");
        if(proxy){info.setProxyHost("127.0.0.1");info.setProxyPort(27018);}
        var manager=new MongoConnectionManager(info,null);
        try {
            Method method=MongoConnectionManager.class.getDeclaredMethod("buildSettings");method.setAccessible(true);
            try{return (MongoClientSettings)method.invoke(manager);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
        }finally{manager.close();}
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        for(String host:new String[]{"fixture.mongocluster.cosmos.azure.com","fixture.global.mongocluster.cosmos.azure.com","fixture.mongocluster.cosmos.azure.cn"}) {
            for(boolean oidc:new boolean[]{false,true}) {
                var s=settings(host,oidc,false);
                require(host.equals(s.getClusterSettings().getSrvHost()),"DocumentDB must use SRV");
                require(s.getSslSettings().isEnabled(),"Azure requires TLS");
                require(!s.getRetryWrites(),"Azure compatible default must disable retryable writes");
                require(s.getConnectionPoolSettings().getMaxConnectionIdleTime(TimeUnit.MILLISECONDS)==120000,"idle timeout");
                if(oidc)require(s.getCredential().getAuthenticationMechanism().getMechanismName().equals("MONGODB-OIDC"),"lost OIDC");
            }
            try{settings(host,true,true);throw new AssertionError("SRV incorrectly forced through one fixed-port tunnel");}catch(IllegalArgumentException expected){require(expected.getMessage().contains("SRV"),"unclear proxy error");}
        }
        for(String host:new String[]{"fixture.mongo.cosmos.azure.com","fixture.mongo.cosmos.azure.cn"}) {
            var s=settings(host,false,false);require(s.getClusterSettings().getSrvHost()==null,"RU endpoint must keep configured port");require(s.getSslSettings().isEnabled()&&!s.getRetryWrites(),"RU TLS/retry policy");
        }
        for(String host:new String[]{"localhost","fixture.mongocluster.cosmos.azure.com.attacker.invalid","unrelated.cosmos.azure.com"}) {
            var s=settings(host,false,false);require(s.getClusterSettings().getSrvHost()==null,"unrelated endpoint classified as DocumentDB");require(s.getRetryWrites(),"ordinary Mongo defaults changed");
        }
        require(settings("localhost",false,true).getClusterSettings().getMode()==com.mongodb.connection.ClusterConnectionMode.SINGLE,"ordinary Mongo tunnel broken");
        System.out.println("U08 settings: SRV/TLS/retry/idle, RU direct, OIDC, proxy rejection and endpoint boundaries passed");
    }
}
