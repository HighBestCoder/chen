import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.web.controller.ProfileController;
import org.jumpserver.chen.web.entity.Profile;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceOuterClass;
import java.lang.reflect.Proxy;

// The instance dialog must list assets from the session asset's org, so the profile carries it.
public class TestProfileOrg {
    public static void main(String[] args) throws Exception {
        var info = new DBConnectInfo();
        info.setDbType("mongodb");
        Datasource datasource = (Datasource) Proxy.newProxyInstance(Datasource.class.getClassLoader(),
                new Class[]{Datasource.class}, (p, m, a) -> m.getName().equals("getConnectInfo") ? info : null);
        var jms = Common.Session.newBuilder().setOrgId("org-tencent").setAsset("t05261_tencent-mongodb").build();
        var session = new JMSSession(jms, datasource, "local", null, ServiceOuterClass.TokenResponse.getDefaultInstance());
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        var method = ProfileController.class.getDeclaredMethod("getProfile");
        method.setAccessible(true);
        var profile = (Profile) method.invoke(new ProfileController());
        if (!"org-tencent".equals(profile.getOrgId())) throw new AssertionError("profile orgId: " + profile.getOrgId());
        if (!"t05261_tencent-mongodb".equals(profile.getAssetName())) throw new AssertionError("profile asset changed");
        System.out.println("Profile org: session asset org returned for the instance dialog");
        System.exit(0);
    }
}
