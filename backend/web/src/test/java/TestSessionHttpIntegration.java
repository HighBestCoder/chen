import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.web.WebDbApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.*;

/** Real embedded HTTP/WebSocket stack; datasource and Core are deliberately not exercised. */
public class TestSessionHttpIntegration {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void rejectHandshake(HttpClient client,String url,String token) throws Exception {
        try {
            var builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
            if(token!=null)builder.subprotocols(token);
            var socket=builder.buildAsync(URI.create(url),new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS);
            socket.abort();throw new AssertionError("unauthorized WebSocket upgrade accepted");
        } catch(ExecutionException expected) {
            require(expected.getCause() instanceof WebSocketHandshakeException,"not an authentication response: "+expected.getCause().getClass());
            require(((WebSocketHandshakeException)expected.getCause()).getResponse().statusCode()==401,"handshake should return 401");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("chen-http-test-");
        Path drivers=Files.createDirectory(root.resolve("drivers"));
        Path files=Files.createDirectory(root.resolve("files"));
        String token=null, secondToken=null;WebSocket socket=null, secondSocket=null;
        try(var context=(ServletWebServerApplicationContext)SpringApplication.run(WebDbApplication.class,
                "--server.port=0","--server.servlet.context-path=/chen","--mock.enable=true",
                "--driver.driver-path="+drivers,"--grpc.client.wisp.address=static://127.0.0.1:1",
                "--grpc.client.wisp.negotiationType=PLAINTEXT","--spring.main.banner-mode=off","--logging.level.root=WARN")) {
            int port=context.getWebServer().getPort();
            String base="http://127.0.0.1:"+port+"/chen";String ws="ws://127.0.0.1:"+port+"/chen/ws/session";
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            require(client.send(HttpRequest.newBuilder(URI.create(base+"/api/profile")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==401,"HTTP protected entry accepted anonymous request");
            rejectHandshake(client,ws,null);rejectHandshake(client,ws,"unknown");
            DBConnectInfo info=new DBConnectInfo();info.setDbType("mysql");
            Datasource ds=(Datasource)Proxy.newProxyInstance(Datasource.class.getClassLoader(),new Class[]{Datasource.class},
                    (p,m,a)->m.getName().equals("getConnectInfo")?info:null);
            BaseSession session=new BaseSession(ds,"127.0.0.1"){@Override public Path getTempPath(){return files;} @Override public String getDatasourceName(){return "instance-A";}};
            token=SessionManager.registerSession(session);CountDownLatch ready=new CountDownLatch(1);
            socket=client.newWebSocketBuilder().subprotocols(token).buildAsync(URI.create(ws),new WebSocket.Listener(){
                final StringBuilder text=new StringBuilder();
                @Override public void onOpen(WebSocket webSocket){webSocket.request(1);}
                @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last){
                    text.append(data);if(last){if(text.toString().contains("set_ready"))ready.countDown();text.setLength(0);}
                    webSocket.request(1);return null;
                }
            }).get(5,TimeUnit.SECONDS);
            require(ready.await(5,TimeUnit.SECONDS),"registered session failed activation over real WebSocket");
            var profile=client.send(HttpRequest.newBuilder(URI.create(base+"/api/profile")).header("token",token).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            require(profile.statusCode()==200 && profile.body().contains("mysql"),"valid identity failed real HTTP profile request");
            // U01: two independent instance sessions share the server, never their identity.
            Path secondFiles=Files.createDirectory(root.resolve("second-files"));
            DBConnectInfo secondInfo=new DBConnectInfo();secondInfo.setDbType("mongodb");
            Datasource secondDs=(Datasource)Proxy.newProxyInstance(Datasource.class.getClassLoader(),new Class[]{Datasource.class},
                    (p,m,a)->m.getName().equals("getConnectInfo")?secondInfo:null);
            BaseSession second=new BaseSession(secondDs,"127.0.0.1") {
                @Override public Path getTempPath(){return secondFiles;}
                @Override public String getDatasourceName(){return "instance-B";}
            };
            secondToken=SessionManager.registerSession(second);
            secondSocket=client.newWebSocketBuilder().subprotocols(secondToken).buildAsync(URI.create(ws),new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS);
            long activationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!second.isActive() && System.nanoTime()<activationDeadline)Thread.sleep(10);
            require(second.isActive() && !token.equals(secondToken),"second instance did not get its own active identity");
            for(int i=0;i<12;i++) {
                var aRequest=HttpRequest.newBuilder(URI.create(base+"/api/profile?asset=instance-B")).header("token",token).build();
                var bRequest=HttpRequest.newBuilder(URI.create(base+"/api/profile?asset=instance-A")).header("token",secondToken).build();
                var aFuture=client.sendAsync(aRequest,HttpResponse.BodyHandlers.ofString());
                var bFuture=client.sendAsync(bRequest,HttpResponse.BodyHandlers.ofString());
                var aResult=aFuture.get(5,TimeUnit.SECONDS);var bResult=bFuture.get(5,TimeUnit.SECONDS);
                require(aResult.statusCode()==200 && aResult.body().contains("instance-A") && aResult.body().contains("mysql") && !aResult.body().contains("instance-B"),"A leaked B identity");
                require(bResult.statusCode()==200 && bResult.body().contains("instance-B") && bResult.body().contains("mongodb") && !bResult.body().contains("instance-A"),"B leaked A identity");
            }
            rejectHandshake(client,ws,token);require(session.isActive(),"duplicate handshake killed original socket");
            Files.writeString(files.resolve("result.csv"),"fixture");
            socket.sendClose(WebSocket.NORMAL_CLOSURE,"").get(3,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((SessionManager.getSession(token)!=null || Files.exists(files)) && System.nanoTime()<deadline)Thread.sleep(10);
            require(SessionManager.getSession(token)==null && !Files.exists(files),"real socket close did not clean session/files");
            require(client.send(HttpRequest.newBuilder(URI.create(base+"/api/profile")).header("token",token).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==401,"closed token retained HTTP access");
            require(second.isActive(),"closing A killed B");
            var surviving=client.send(HttpRequest.newBuilder(URI.create(base+"/api/profile")).header("token",secondToken).build(),HttpResponse.BodyHandlers.ofString());
            require(surviving.statusCode()==200 && surviving.body().contains("instance-B"),"B lost identity after A closed");
            second.close();
            System.out.println("OK: embedded HTTP and WebSocket authentication/activation/duplicate rejection/close cleanup; two-instance concurrent HTTP identity and independent close");
        } finally {
            if(secondSocket!=null)secondSocket.abort();if(secondToken!=null)SessionManager.unregisterSession(secondToken);
            if(socket!=null)socket.abort();if(token!=null)SessionManager.unregisterSession(token);
            if(Files.exists(root))try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
