package com.swapops.server.common.web;

import com.swapops.server.user.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户端 token 拦截器：/user/**（除登录）必须携带 X-User-Token；
 * 认证通过把 userId 写入 UserContext，afterCompletion 清理（防线程池串号）。
 */
@Component
public class UserAuthInterceptor implements HandlerInterceptor {

    private static final String USER_TOKEN_HEADER = "X-User-Token";

    private final UserAccountService userAccountService;

    public UserAuthInterceptor(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Long userId = userAccountService.resolve(request.getHeader(USER_TOKEN_HEADER));
        if (userId == null) {
            WebErrors.write(response, 401, 401, "用户端未认证");
            return false;
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
    }
}
