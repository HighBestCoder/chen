package org.jumpserver.chen.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;


@Component
@Slf4j
public class WebExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (response.isCommitted()) {
            return null;
        }
        boolean denied = org.jumpserver.chen.framework.datasource.error.SqlPermissionErrorClassifier.isPermissionDenied(ex)
                || org.jumpserver.chen.modules.mongodb.MongoPermissionErrorClassifier.isPermissionDenied(ex);
        response.setStatus(denied ? 403 : 500);
        response.setContentType("text/plain;charset=UTF-8");
        try {
            response.getWriter().write(denied ? org.jumpserver.chen.framework.i18n.MessageUtils.get("msg.error.no_operation_permission")
                    : ex instanceof ChenException ? ex.getMessage() : "Internal server error");
        } catch (IOException e) {
            log.error("Failed to write error response", e);
        }
        log.error("Request failed", ex);
        return new ModelAndView();
    }
}
