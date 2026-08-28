package com.flower.spirit.Interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.flower.spirit.config.Global;
import com.flower.spirit.entity.UserEntity;

/**
 * 
 * <p>
 * Title: LoginInterceptor
 * </p>
 * 
 * <p>
 * Description:登录拦截器
 * </p>
 * 
 * @author QingFeng
 * 
 * @date 2020年8月14日
 * 
 */
public class LoginInterceptor implements HandlerInterceptor {

    private Logger logger = LoggerFactory.getLogger(LoginInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) throws Exception {

        String uri = request.getRequestURI().toString();
        UserEntity user = (UserEntity) request.getSession().getAttribute(Global.user_session_key);

        // 如果用户已登录，允许访问所有路径
        if (user != null) {
            return true;
        }

        // 资源路径：未登录时需要验证token
        if (uri.startsWith("/resources") || uri.startsWith("/cos")) {
            String apptoken = request.getParameter("apptoken");
            // token为空或token不匹配时拒绝访问
            if (apptoken == null || !(Global.apptoken.equals(apptoken) || Global.readonlytoken.equals(apptoken))) {
                logger.warn("资源访问未授权: {}", uri);
                response.sendRedirect("/admin/login");
                return false;
            }
            return true;
        }
        // 其他路径：必须用户登录
        else {
            logger.warn("需要登录: {}", uri);
            if (uri.startsWith("/admin/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(new ObjectMapper().writeValueAsString(
                        Map.of("resCode", "401", "message", "登录已失效，请重新登录")));
                return false;
            }
            response.sendRedirect("/admin/login");
            return false;
        }
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
    }

}
