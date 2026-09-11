import jakarta.servlet.http.HttpServletRequest;
import org.jumpserver.chen.web.controller.ClientAddress;
import java.lang.reflect.Proxy;

public class TestS08ClientAddress {
    static void check(String peer, String header, String trusted, String expected) {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(), new Class[]{HttpServletRequest.class},
            (proxy, method, args) -> method.getName().equals("getRemoteAddr") ? peer :
                method.getName().equals("getHeader") ? header : null);
        String actual = ClientAddress.resolve(request, trusted);
        if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        check("192.0.2.1", "198.51.100.99", "", "192.0.2.1");
        check("192.0.2.1", "198.51.100.99", "10.0.0.2", "192.0.2.1");
        check("10.0.0.2", "198.51.100.99, 192.0.2.1", "10.0.0.2", "192.0.2.1");
        check("10.0.0.2", "192.0.2.1, 10.0.0.3", "10.0.0.2,10.0.0.3", "192.0.2.1");
        check("10.0.0.2", "garbage", "10.0.0.2", "10.0.0.2");
        check("::1", "2001:db8::1", "::1", "2001:db8::1");
        System.out.println("S08 client address: 6 cases passed");
    }
}
