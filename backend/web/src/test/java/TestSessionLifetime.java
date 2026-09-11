import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.wisp.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.concurrent.*;

public class TestSessionLifetime {
    public static void main(String[] args) throws Exception {
        var template=ServiceOuterClass.TokenResponse.getDefaultInstance().getData();
        var data=template.toBuilder().setSetting(template.getSetting().toBuilder().setMaxIdleTime(100).setMaxSessionTime(1))
            .setExpireInfo(template.getExpireInfo().toBuilder().setExpireAt(Instant.now().getEpochSecond()+86400)).build();
        CountDownLatch timedOut=new CountDownLatch(1);
        String[] reason={null};boolean[] active={true};
        JMSSession session=new JMSSession(Common.Session.newBuilder().setDateStart(Instant.now().getEpochSecond()-7200).build(),
                null,"local",null,ServiceOuterClass.TokenResponse.newBuilder().setData(data).build()) {
            @Override public boolean isActive(){return active[0];}
            @Override public void close(String message,String cause,Object... args){reason[0]=cause;active[0]=false;timedOut.countDown();}
        };
        java.util.List<String> failures=new java.util.ArrayList<>();
        Field commandHandler=JMSSession.class.getDeclaredField("commandHandler");commandHandler.setAccessible(true);
        Class<?> handlerType=commandHandler.getType();
        commandHandler.set(session,Proxy.newProxyInstance(handlerType.getClassLoader(),new Class[]{handlerType},(p,m,a)->null));
        Field activity=JMSSession.class.getDeclaredField("lastActiveTime");activity.setAccessible(true);activity.setLong(session,0);
        session.recordCommand(new org.jumpserver.chen.framework.jms.entity.CommandRecord("db.items.find({})"));
        if(activity.getLong(session)!=0)failures.add("expired session was revived by command audit");
        Method start=JMSSession.class.getDeclaredMethod("startWaitIdleTime");start.setAccessible(true);
        try {
            start.invoke(session);
            if(!timedOut.await(7,TimeUnit.SECONDS) || !"max_session_timeout".equals(reason[0]))
                failures.add("recent activity extended session past absolute lifetime");
        } finally {
            active[0]=false;
            Field worker=JMSSession.class.getDeclaredField("waitIdleTimeThread");worker.setAccessible(true);
            ((Thread)worker.get(session)).interrupt();
        }
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("OK: absolute lifetime expires despite recent activity");
    }
}
