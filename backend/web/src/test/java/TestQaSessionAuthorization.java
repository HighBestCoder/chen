import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.wisp.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class TestQaSessionAuthorization {
    public static void main(String[] args) {
        long now=Instant.now().getEpochSecond();
        var data=Common.TokenAuthInfo.newBuilder()
            .setSetting(Common.ComponentSetting.newBuilder().setMaxIdleTime(60).setMaxSessionTime(1))
            .setExpireInfo(Common.ExpireInfo.newBuilder().setExpireAt(now+3600))
            .setPermission(Common.Permission.newBuilder().setEnableDownload(true));
        var session=new JMSSession(Common.Session.newBuilder().setDateStart(now).build(),null,"fixture",null,
                ServiceOuterClass.TokenResponse.newBuilder().setData(data).build()) {
            @Override public boolean isActive(){return true;}
        };
        AtomicInteger calls=new AtomicInteger();
        session.setAuthorizationCheck(()->{calls.incrementAndGet();return false;});
        if(!session.allowsExecution() || session.canDownload())throw new AssertionError("DEF-12 connect-only authorization permitted download");
        session.setAuthorizationCheck(()->true);
        if(!session.canDownload())throw new AssertionError("granted download denied");
        session.setAuthorizationCheck(()->{throw new IllegalStateException("revoked");});
        if(session.allowsExecution() || session.canDownload())throw new AssertionError("DEF-19 revoked session allowed execution");
        session.setAuthorizationCheck(()->true);
        if(session.allowsExecution())throw new AssertionError("revoked session revived");
        if(calls.get()<2)throw new AssertionError("authorization not rechecked");
        System.out.println("DEF-12/19 current download actions and connect revocation deny without reviving old session");
    }
}
