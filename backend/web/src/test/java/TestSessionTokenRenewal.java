import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import com.mongodb.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import java.sql.Driver;

public class TestSessionTokenRenewal {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static void rejects(Runnable run) { try { run.run(); throw new AssertionError("Expected rejection"); } catch (IllegalStateException expected) { check(!expected.toString().contains("sensitive-fixture"), "error leaked credential"); } }
    static class Time extends Clock {
        long now = Instant.now().getEpochSecond();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(now); }
    }
    static void lifecycle() throws Exception {
        var clock = new Time(); var calls = new AtomicInteger(); var open = new AtomicBoolean(true);
        var provider = new SessionTokenProvider(new SessionTokenProvider.Credential("old", clock.now + 120),
                () -> { calls.incrementAndGet(); return new SessionTokenProvider.Credential("fresh", clock.now + 3600); }, open::get, clock);
        check(provider.current().token().equals("old") && calls.get()==0, "unnecessary acquisition");
        clock.now += 61;
        var executor = Executors.newFixedThreadPool(12);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i=0;i<24;i++) futures.add(executor.submit(() -> provider.current().token()));
            for (var result:futures) check(result.get(3,TimeUnit.SECONDS).equals("fresh"), "inconsistent snapshot");
        } finally {executor.shutdownNow();}
        check(calls.get()==1, "concurrent duplicate acquisition");
        open.set(false); rejects(provider::current); check(calls.get()==1,"closed session acquired");
        check(!new SessionTokenProvider.Credential("sensitive-fixture",1).toString().contains("sensitive-fixture"), "redaction");
    }
    static void failureAndClosure() {
        var clock=new Time(); var open=new AtomicBoolean(true); var calls=new AtomicInteger();
        var provider=new SessionTokenProvider(new SessionTokenProvider.Credential("old",clock.now-1),()->{
            if(calls.incrementAndGet()==1) throw new IllegalStateException("sensitive-fixture");
            return new SessionTokenProvider.Credential("new",clock.now+3600);
        },open::get,clock);
        rejects(provider::current); rejects(provider::current); check(calls.get()==1,"missing failure backoff");
        clock.now+=5; check(provider.current().token().equals("new"),"failed refresh cannot recover");
        var closing=new SessionTokenProvider(new SessionTokenProvider.Credential("old",0),()->{
            open.set(false);return new SessionTokenProvider.Credential("new",clock.now+3600);
        },open::get,clock);
        rejects(closing::current);
        open.set(true);
        for(var bad:List.of(new SessionTokenProvider.Credential("",clock.now+3600),new SessionTokenProvider.Credential("expired",clock.now))) {
            var invalid=new SessionTokenProvider(new SessionTokenProvider.Credential("old",0),()->bad,open::get,clock);
            rejects(invalid::current);
        }
    }
    static void jdbc() throws Exception {
        for(String type:List.of("postgresql","mysql","sqlserver")) {
            var seen=new ArrayList<Properties>();
            Driver driver=(Driver)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{Driver.class},(p,m,a)->{
                if(m.getName().equals("connect")) seen.add((Properties)a[1]);return null;
            });
            var clock=new Time();var info=new DBConnectInfo();info.getOptions().put("token_expires_at",1);
            info.setTokenProvider(new SessionTokenProvider(new SessionTokenProvider.Credential("old",0),
                    ()->new SessionTokenProvider.Credential("fresh",clock.now+3600),()->true,clock));
            String key=type.equals("sqlserver")?"accessToken":"password";
            var defaults=new Properties();defaults.setProperty("sslmode","verify-full");
            var properties=new Properties(defaults);properties.setProperty(key,"old");
            new TokenGuardDriver(driver,info).connect("jdbc:"+type+":fixture",properties);
            check(seen.size()==1 && seen.get(0).getProperty(key).equals("fresh"),type+" stale credential");
            check(seen.get(0).getProperty("sslmode").equals("verify-full"),"TLS lost");
            check(properties.getProperty(key).equals("old"),"pool properties mutated");
        }
    }
    static void mongo() throws Exception {
        var clock=new Time();var info=new DBConnectInfo(); info.setHost("fixture");info.setPort(27017);info.setDb("admin");info.setPassword("old");
        info.getOptions().put("relationalAuthDecision","V2_OIDC_TOKEN_REQUIRED");info.getOptions().put("token_expires_at",clock.now+3600);
        var calls=new AtomicInteger();
        info.setTokenProvider(new SessionTokenProvider(new SessionTokenProvider.Credential("old",clock.now+3600),()->{
            calls.incrementAndGet();return new SessionTokenProvider.Credential("renewed",clock.now+3600);
        },()->true,clock));
        var manager=new MongoConnectionManager(info,null);
        try {
            var build=MongoConnectionManager.class.getDeclaredMethod("buildSettings");build.setAccessible(true);
            var settings=(MongoClientSettings)build.invoke(manager);
            var cb=settings.getCredential().getMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY,(MongoCredential.OidcCallback)null);
            check(cb.onRequest(null).getAccessToken().equals("old"),"initial Mongo token");
            clock.now+=3601;
            check(cb.onRequest(null).getAccessToken().equals("renewed") && calls.get()==1,"Mongo callback captured expired token");
        } finally {manager.close();}
    }
    public static void main(String[] args) throws Exception {
        lifecycle();failureAndClosure();jdbc();mongo();
        System.out.println("OK: renewal concurrency, expiry, closure, recovery, JDBC three protocols and Mongo OIDC");
    }
}
