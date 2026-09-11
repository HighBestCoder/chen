import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.web.hook.RegisterJMSEvent;
import org.jumpserver.chen.web.config.MockConfig;
import org.jumpserver.chen.wisp.ServiceOuterClass;
import java.lang.reflect.Field;
import java.util.concurrent.*;

public class TestSessionTaskReconnect {
    public static void main(String[] args) throws Exception {
        BlockingQueue<StreamObserver<ServiceOuterClass.TaskResponse>> streams=new LinkedBlockingQueue<>();
        RegisterJMSEvent tasks=new RegisterJMSEvent(){
            @Override protected StreamObserver<ServiceOuterClass.FinishedTaskRequest> openTaskStream(StreamObserver<ServiceOuterClass.TaskResponse> observer){
                streams.add(observer);
                return new StreamObserver<>() {
                    public void onNext(ServiceOuterClass.FinishedTaskRequest r){}
                    public void onError(Throwable e){}
                    public void onCompleted(){}
                };
            }
        };
        Field config=RegisterJMSEvent.class.getDeclaredField("mockConfig");config.setAccessible(true);config.set(tasks,new MockConfig());
        try {
            tasks.startSessionKiller();var first=streams.poll(1,TimeUnit.SECONDS);
            if(first==null)throw new AssertionError("task stream not opened");
            first.onError(new RuntimeException("injected disconnect"));
            var second=streams.poll(3,TimeUnit.SECONDS);if(second==null)throw new AssertionError("failed stream never reconnected");
            second.onCompleted();if(streams.poll(3,TimeUnit.SECONDS)==null)throw new AssertionError("completed stream never reconnected");
            tasks.stopSessionTasks();first.onError(new RuntimeException("late callback"));
            if(streams.poll(1500,TimeUnit.MILLISECONDS)!=null)throw new AssertionError("shutdown reopened stream");
        } finally { tasks.stopSessionTasks(); }
        System.out.println("OK: session task stream reconnects after failure/completion and stops on shutdown");
    }
}
