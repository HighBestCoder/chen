package org.jumpserver.chen.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.AuthRequest;
import org.jumpserver.chen.web.entity.AuthResponse;
import org.jumpserver.chen.web.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @org.springframework.beans.factory.annotation.Value("${chen.trusted-proxies:}")
    private String trustedProxies = "";

    @Autowired
    private SessionService sessionService;

    @PostMapping("")
    public AuthResponse auth(HttpServletRequest request, @RequestBody AuthRequest authRequest) {
        String token = authRequest.getToken();
        Session sess = sessionService.createNewSession(token, getRemoteAddr(request));

        sess.setEnableAutoComplete(!authRequest.isDisableAutoHash());

        var lang = getLanguage(request);
        sess.setLocale(lang);

        var chenToken = SessionManager.registerSession(sess);
        return new AuthResponse(chenToken, lang.toLanguageTag());
    }

    private String getRemoteAddr(HttpServletRequest request) {
        return ClientAddress.resolve(request, trustedProxies);
    }

    private Locale getLanguage(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies != null) {
            for (var cookie : cookies) {
                if (cookie.getName().equals("django_language")) {
                    switch (cookie.getValue()) {
                        case "en":
                            return Locale.US;
                        case "ja":
                            return Locale.JAPAN;
                    }
                }
            }
        }
        return Locale.CHINA;
    }
}
