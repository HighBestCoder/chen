package org.jumpserver.chen.framework.driver;

import lombok.Getter;

import java.net.URL;
import java.net.URLClassLoader;

public class DriverClassLoader extends URLClassLoader {

    @Getter
    private final String jarName;

    public DriverClassLoader(String jarName, URL url) {
        // The application loader owns custom TLS/socket factories in a Boot executable jar.
        super(new URL[]{url}, DriverClassLoader.class.getClassLoader());
        this.jarName = jarName;
    }


}
