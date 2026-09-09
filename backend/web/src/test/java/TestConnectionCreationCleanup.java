import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.postgresql.PostgresqlDatasource;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import java.lang.reflect.Field;
import java.util.*;

/** Real local gRPC calls to a controlled Core service; no database or business Core. */
public class TestConnectionCreationCleanup {
    static final ServiceOuterClass.Status OK = ServiceOuterClass.Status.newBuilder().setOk(true).build();
    public static class FixtureDatasource extends PostgresqlDatasource {
        static int closed;
        public FixtureDatasource(DBConnectInfo info) { super(info); }
        @Override public void close() { closed++; super.close(); }
    }
    static class Core extends ServiceGrpc.ServiceImplBase {
        String mode; int created, finished, forwardsDeleted; List<String> ids = new ArrayList<>();
        static <T> void reply(StreamObserver<T> out, T value) { out.onNext(value); out.onCompleted(); }
        @Override public void getTokenAuthInfo(ServiceOuterClass.TokenRequest request, StreamObserver<ServiceOuterClass.TokenResponse> out) {
            var asset = Common.Asset.newBuilder().setAddress("fixture-host")
                    .addProtocols(Common.Protocol.newBuilder().setName(mode.equals("datasource") ? "missing-driver" : "fixture").setPort(5432));
            var data = Common.TokenAuthInfo.newBuilder().setAsset(asset)
                    .setPlatform(Common.Platform.newBuilder().addProtocols(Common.PlatformProtocol.newBuilder().setName("fixture")));
            if (!mode.equals("datasource")) data.addGateways(Common.Gateway.getDefaultInstance());
            reply(out, ServiceOuterClass.TokenResponse.newBuilder().setStatus(OK).setData(data).build());
        }
        @Override public void createSession(ServiceOuterClass.SessionCreateRequest req, StreamObserver<ServiceOuterClass.SessionCreateResponse> out) {
            created++; reply(out, ServiceOuterClass.SessionCreateResponse.newBuilder().setStatus(OK).setData(req.getData().toBuilder().setId("fixture-"+created)).build());
        }
        @Override public void finishSession(ServiceOuterClass.SessionFinishRequest req, StreamObserver<ServiceOuterClass.SessionFinishResp> out) {
            finished++; ids.add(req.getId()); reply(out, ServiceOuterClass.SessionFinishResp.newBuilder().setStatus(OK).build());
        }
        @Override public void createForward(ServiceOuterClass.ForwardRequest req, StreamObserver<ServiceOuterClass.ForwardResponse> out) {
            if (mode.equals("transport")) out.onError(Status.UNAVAILABLE.withDescription("injected transport failure").asRuntimeException());
            else if (mode.equals("invalid-port") || mode.equals("success")) {
                reply(out, ServiceOuterClass.ForwardResponse.newBuilder().setStatus(OK).setId("fixture-forward").setPort(mode.equals("success") ? 15432 : 0).build());
            } else reply(out, ServiceOuterClass.ForwardResponse.newBuilder().setStatus(ServiceOuterClass.Status.newBuilder().setOk(false).setErr("injected gateway rejection")).build());
        }
        @Override public void deleteForward(ServiceOuterClass.ForwardDeleteRequest req, StreamObserver<ServiceOuterClass.StatusResponse> out) {
            if (!req.getId().equals("fixture-forward")) throw new AssertionError("wrong forward closed");
            forwardsDeleted++; reply(out, ServiceOuterClass.StatusResponse.newBuilder().setStatus(OK).build());
        }
        @Override public void recordSessionLifecycleLog(ServiceOuterClass.SessionLifecycleLogRequest req, StreamObserver<ServiceOuterClass.StatusResponse> out) {
            reply(out, ServiceOuterClass.StatusResponse.newBuilder().setStatus(OK).build());
        }
    }
    public static void main(String[] args) throws Exception {
        Core core = new Core(); Server server = ServerBuilder.forPort(0).directExecutor().addService(core).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        Field registry = DatasourceFactory.class.getDeclaredField("DATASOURCE_MAP"); registry.setAccessible(true);
        @SuppressWarnings("unchecked") var factories = (Map<String, Class<? extends Datasource>>) registry.get(null);
        factories.put("fixture", FixtureDatasource.class);
        int failures = 0;
        try {
            JmsSessionService service = new JmsSessionService(); Field stub = JmsSessionService.class.getDeclaredField("serviceBlockingStub"); stub.setAccessible(true);
            stub.set(service, ServiceGrpc.newBlockingStub(channel));
            for (String mode : List.of("datasource", "transport", "rejected", "invalid-port")) {
                core.mode = mode;
                try { service.createNewSession("fixture-token", "127.0.0.1"); failures++; }
                catch (RuntimeException expected) { /* Assertions below verify cleanup even on failure. */ }
                if (core.finished != core.created) failures++;
            }
            if (!core.ids.equals(List.of("fixture-1", "fixture-2", "fixture-3", "fixture-4"))) failures++;
            if (FixtureDatasource.closed != 3 || core.forwardsDeleted != 1) failures++;
            core.mode = "success";
            var session = service.createNewSession("fixture-token", "127.0.0.1");
            if (core.finished != 4 || core.created != 5 || session.getDatasource().getConnectInfo().getProxyPort() != 15432) failures++;
            session.close(); session.close();
            if (core.finished != 5 || core.forwardsDeleted != 2 || FixtureDatasource.closed != 4) failures++;
        } finally { factories.remove("fixture"); channel.shutdownNow(); server.shutdownNow(); }
        if (failures != 0) throw new AssertionError(failures+" creation cleanup failures; created="+core.created+" finished="+core.finished+" datasourceClosed="+FixtureDatasource.closed);
        System.out.println("OK: datasource/gateway/transport failures finish exactly their Core sessions and close constructed datasources");
    }
}
