import org.jumpserver.chen.framework.utils.*;
import org.jumpserver.chen.framework.session.SessionManager;
import java.util.concurrent.*;

public class TestS09Utilities {
    public static class Parent { private Number number; public void setNumber(Number value){number=value;} }
    public static class Bean extends Parent {
        private String text="old"; private boolean enabled;
        public void setText(String value){text=value;}
        public void setEnabled(boolean value){enabled=value;}
        private String rejected;
        public void setRejected(String value){throw new IllegalStateException("setter failed");}
    }
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        if(args.length>0 && args[0].equals("threads")){ threads(); return; }
        Bean bean=new Bean();
        ReflectUtils.setFieldValue(bean,"text",null); check(bean.text==null,"SQL NULL not assigned");
        ReflectUtils.setFieldValue(bean,"enabled",true); check(bean.enabled,"primitive setter not assigned");
        ReflectUtils.setFieldValue(bean,"number",Integer.valueOf(7)); check(((Parent)bean).number.intValue()==7,"inherited assignable setter");
        boolean failed=false;try{ReflectUtils.setFieldValue(bean,"rejected","x");}catch(RuntimeException e){failed=true;}
        check(failed,"setter error swallowed");
        failed=false;try{ReflectUtils.setFieldValue(bean,"enabled","yes");}catch(RuntimeException e){failed=true;}
        check(failed,"incompatible value silently accepted");
        threads();
        var output = new java.io.StringWriter();
        long[] clock = {10_000_000_000L};
        var recording = new org.jumpserver.chen.framework.jms.asciinema.AsciinemaWriter(output, () -> clock[0]);
        recording.writeHeader();
        clock[0] += 125_000_000L; recording.writeRow(new byte[]{65});
        clock[0] += 250_000_000L; recording.writeRow(new byte[]{66});
        check(output.toString().contains("[0.125,") && output.toString().contains("[0.375,"), "recording elapsed time");
        long before = java.time.Instant.now().toEpochMilli();
        long now = TimeUtils.getNowUnixNanoTIme() / 1_000_000;
        check(now >= before && now <= java.time.Instant.now().toEpochMilli(), "Unix epoch conversion");
        System.out.println("OK: reflection NULL/primitive/inheritance/errors; thread context/cancellation/interruption");
    }
    static void threads()throws Exception {
        SessionManager.setContext("outer");
        try {
            try {new ThreadUtils.SessionCtxRunnable(()->{check("inner".equals(SessionManager.getContextToken()),"inner context");throw new IllegalStateException();},"inner").run();}catch(IllegalStateException expected){}
            check("outer".equals(SessionManager.getContextToken()),"session context leaked");
            CountDownLatch started=new CountDownLatch(1), stopped=new CountDownLatch(1);
            Thread caller=new Thread(()->{
                try{ThreadUtils.runWithTimeout(new ThreadUtils.SessionCtxRunnable(()->{
                    started.countDown();try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException expected){stopped.countDown();}
                },"worker"),1);}catch(RuntimeException expected){}
            });
            caller.start();check(started.await(2,TimeUnit.SECONDS),"worker did not start");caller.join(2500);
            check(!caller.isAlive() && stopped.await(300,TimeUnit.MILLISECONDS),"timed out work not cancelled");
            Thread.currentThread().interrupt();
            try{ThreadUtils.runWithTimeout(new ThreadUtils.SessionCtxRunnable(()->{},"x"),1);}catch(RuntimeException expected){}
            check(Thread.currentThread().isInterrupted(),"interrupt flag lost");
        }finally{Thread.interrupted();SessionManager.setContext(null);}
    }
}
