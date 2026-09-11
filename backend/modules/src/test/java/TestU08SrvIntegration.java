import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Driver SRV discovery against local TLS Mongo; not an Azure service emulator. */
public class TestU08SrvIntegration extends TestConnectionTlsIntegration {
    public static void main(String[] args)throws Exception {
        var info=info("mongodb");info.setHost("fixture.mongocluster.cosmos.azure.com");
        var manager=new MongoConnectionManager(info,null);
        Method method=MongoConnectionManager.class.getDeclaredMethod("buildSettings");method.setAccessible(true);
        var production=(MongoClientSettings)method.invoke(manager);
        AtomicInteger srvLookups=new AtomicInteger();
        var settings=MongoClientSettings.builder(production).dnsClient((name,type)->{
            if(type.equals("SRV")) {
                if(!name.equals("_mongodb._tcp.fixture.mongocluster.cosmos.azure.com"))throw new AssertionError("unexpected SRV lookup");
                srvLookups.incrementAndGet();return List.of("0 0 27017 mongo.mongocluster.cosmos.azure.com");
            }
            if(type.equals("TXT"))return List.of();
            throw new AssertionError("unexpected DNS type "+type);
        }).inetAddressResolver(name->{
            if(!name.equals("mongo.mongocluster.cosmos.azure.com"))throw new AssertionError("unexpected connection target");
            return List.of(InetAddress.getByAddress(name,InetAddress.getByName("mongo.fixture").getAddress()));
        }).build();
        try(var client=MongoClients.create(settings)) {
            var collection=client.getDatabase("u08_srv").getCollection("rows");
            collection.insertOne(new Document("_id",1).append("value","srv-tls"));
            if(!"srv-tls".equals(collection.find(new Document("_id",1)).first().getString("value")))throw new AssertionError("SRV query failed");
            collection.deleteOne(new Document("_id",1));
            if(collection.countDocuments()!=0||srvLookups.get()==0)throw new AssertionError("SRV discovery or delete not executed");
            System.out.println("PASS U08: production SRV settings, injected DNS, real driver TLS hostname verification and authenticated CRUD");
        }finally{manager.close();}
    }
}
