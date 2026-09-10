import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.security.MessageDigest;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.driver.DriverManager;
import org.jumpserver.chen.web.config.DriverConfig;
import org.jumpserver.chen.web.hook.RegisterDriverClassLoader;

public class TestU06DriverContract {
    public static void main(String[] args)throws Exception {
        Properties contract=new Properties();try(var in=Files.newInputStream(Path.of("drivers/contract.properties"))){contract.load(in);}
        var hook=new RegisterDriverClassLoader();var field=RegisterDriverClassLoader.class.getDeclaredField("driverConfig");field.setAccessible(true);field.set(hook,new DriverConfig());hook.registerDriverClassLoader();
        for(String type:List.of("postgresql","mysql","sqlserver")) {
            String filename=contract.getProperty(type+".jar");Path jar=Path.of("drivers",type,filename);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
            if(!hash.equals(contract.getProperty(type+".sha256")))throw new AssertionError(type+" driver checksum mismatch");
            try(var file=new JarFile(jar.toFile())) {
                var attrs=file.getManifest().getMainAttributes();String version=attrs.getValue("Bundle-Version");
                if(!contract.getProperty(type+".version").equals(version))throw new AssertionError(type+" manifest version mismatch");
            }
            if(DriverManager.getDrivers(type)==null || DriverManager.getDrivers(type).isEmpty())throw new AssertionError(type+" driver not registered");
            DBConnectInfo info=new DBConnectInfo();info.setDbType(type);
            var datasource=switch(type) {
                case "postgresql" -> new org.jumpserver.chen.modules.postgresql.PostgresqlDatasource(info);
                case "mysql" -> new org.jumpserver.chen.modules.mysql.MysqlDatasource(info);
                default -> new org.jumpserver.chen.modules.sqlserver.SQLServerDatasource(info);
            };
            var manager=(org.jumpserver.chen.framework.datasource.base.BaseConnectionManager)datasource.getConnectionManager();
            try {
                var driver=manager.getDriver();String selected=driver.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
                if(selected.startsWith("nested:") && selected.endsWith("/!BOOT-INF/lib/"+filename+"!/")) {
                    // Boot may resolve the same driver from its bundled dependency before the external registry.
                    String outer=selected.substring("nested:".length(),selected.indexOf("/!BOOT-INF/lib/"));
                    try(var boot=new JarFile(outer);var input=boot.getInputStream(boot.getJarEntry("BOOT-INF/lib/"+filename))) {
                        String embedded=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                        if(!embedded.equals(hash))throw new AssertionError(type+" embedded driver differs from distribution");
                    }
                } else if(!selected.endsWith("/"+filename))throw new AssertionError(type+" selected a different driver: "+selected);
                System.out.println("PASS U06 "+type+" exact shipped version/hash/selected class: "+contract.getProperty(type+".version"));
            } finally {manager.close();}
        }
    }
}
