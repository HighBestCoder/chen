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
        // DEF-31: a changed asset address warns once and keeps the session usable, even without a console.
        java.util.List<String> shown=new java.util.ArrayList<>();
        var controller=(org.jumpserver.chen.framework.session.controller.Controller)java.lang.reflect.Proxy.newProxyInstance(
            TestQaSessionAuthorization.class.getClassLoader(),new Class[]{org.jumpserver.chen.framework.session.controller.Controller.class},
            (p,m,a)->{if(m.getName().equals("showMessage"))shown.add(a[0]+":"+a[1]);return null;});
        boolean[] console={false};
        var moved=new JMSSession(Common.Session.newBuilder().setDateStart(now).build(),null,"fixture",null,
                ServiceOuterClass.TokenResponse.newBuilder().setData(data).build()) {
            @Override public boolean isActive(){return true;}
            @Override public org.jumpserver.chen.framework.session.controller.Controller getController(){return console[0]?controller:null;}
        };
        moved.setAuthorizationCheck(()->{moved.warnTargetChanged();return true;});
        if(!moved.allowsExecution())throw new AssertionError("DEF-31 warning without a console denied the session");
        var later=new JMSSession(Common.Session.newBuilder().setDateStart(now).build(),null,"fixture",null,
                ServiceOuterClass.TokenResponse.newBuilder().setData(data).build()) {
            @Override public boolean isActive(){return true;}
            @Override public org.jumpserver.chen.framework.session.controller.Controller getController(){return controller;}
        };
        later.setAuthorizationCheck(()->{later.warnTargetChanged();return true;});
        if(!later.allowsExecution()||!later.allowsExecution())throw new AssertionError("DEF-31 target change denied the session");
        if(shown.size()!=1||!shown.get(0).startsWith("WARNING:"))throw new AssertionError("DEF-31 warning not shown exactly once: "+shown);
        System.out.println("DEF-12/19 current download actions and connect revocation deny without reviving old session; DEF-31 target change warns once");
    }
}
