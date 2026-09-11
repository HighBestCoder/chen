import java.net.URLClassLoader;
import java.nio.file.Path;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.framework.driver.DriverManager;
import org.jumpserver.chen.web.config.DriverConfig;
import org.jumpserver.chen.web.hook.RegisterDriverClassLoader;

/** Exercises both an isolated application loader and the actual shipped driver directory. */
public class TestDriverLoading {
    public static void main(String[] args) throws Exception {
        var location=DriverClassLoader.class.getProtectionDomain().getCodeSource().getLocation();
        if(args.length==0) try(var application=new URLClassLoader(new java.net.URL[]{location},ClassLoader.getPlatformClassLoader())) {
            var type=application.loadClass(DriverClassLoader.class.getName());
            try(var driver=(URLClassLoader)type.getConstructor(String.class,java.net.URL.class).newInstance("fixture.jar",Path.of("fixture.jar").toUri().toURL())) {
                if(driver.getParent()!=application)throw new AssertionError("Driver cannot see application TLS/socket factories under an isolated application loader");
            }
        }
        var hook=new RegisterDriverClassLoader();
        var field=RegisterDriverClassLoader.class.getDeclaredField("driverConfig");field.setAccessible(true);
        field.set(hook,new DriverConfig());hook.registerDriverClassLoader();
        for(var spec:new String[][]{{"postgresql","org.postgresql.Driver"},{"mysql","com.mysql.cj.jdbc.Driver"},{"sqlserver","com.microsoft.sqlserver.jdbc.SQLServerDriver"}}) {
            for(var loader:DriverManager.getDrivers(spec[0])) {
                if(loader.getParent()!=DriverClassLoader.class.getClassLoader())throw new AssertionError("Wrong parent in runtime package");
                var driver=(java.sql.Driver)loader.loadClass(spec[1]).getDeclaredConstructor().newInstance();
                if(driver.getMajorVersion()<=0)throw new AssertionError("Invalid driver version");
                if(loader.loadClass("org.jumpserver.chen.framework.ssl.PemSslSocketFactory")!=org.jumpserver.chen.framework.ssl.PemSslSocketFactory.class)
                    throw new AssertionError("Application factory not visible");
                System.out.println("PASS "+spec[0]+" "+loader.getJarName()+" version="+driver.getMajorVersion()+"."+driver.getMinorVersion());
            }
        }
        Class.forName("com.mongodb.client.MongoClients");
        Class.forName("org.jumpserver.chen.modules.mysql.MysqlGatewaySocketFactory");
        System.out.println("OK: application parent, shipped JDBC drivers and native Mongo dependency");
    }
}
