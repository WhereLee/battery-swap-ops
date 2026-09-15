package com.swapops.server.admin;

import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.controller.AdminAuthController;
import com.swapops.server.admin.enums.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端点治理守卫（S7 WP-A，审查建议）：
 * 反射扫描全部 /admin/** 控制器——每个映射方法必须有 @PreAuthorize 且权限码存在于 AdminRole；
 * 写方法（POST/PUT/DELETE）必须同时有 @AdminLog。防实现期漏挂注解导致越权/漏审计。
 */
@DisplayName("管理端点注解覆盖守卫")
class AdminEndpointGuardTest {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    @Test
    @DisplayName("全部 /admin/** 端点：@PreAuthorize 齐全、权限码合法、写操作有审计")
    void 端点注解覆盖() throws Exception {
        List<Class<?>> controllers = scanAdminControllers();
        assertThat(controllers).isNotEmpty();

        List<String> problems = new ArrayList<>();
        int endpointCount = 0;
        for (Class<?> controller : controllers) {
            if (controller == AdminAuthController.class) {
                continue; // 登录免鉴权（安全链白名单）/登出仅需认证，无权限码
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (!isMapped(method)) {
                    continue;
                }
                endpointCount++;
                String id = controller.getSimpleName() + "#" + method.getName();
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (preAuthorize == null) {
                    problems.add(id + " 缺 @PreAuthorize");
                } else {
                    Matcher matcher = AUTHORITY.matcher(preAuthorize.value());
                    if (!matcher.find()) {
                        problems.add(id + " @PreAuthorize 形态非法: " + preAuthorize.value());
                    } else if (!AdminRole.ALL_CODES.contains(matcher.group(1))) {
                        problems.add(id + " 权限码未定义: " + matcher.group(1));
                    }
                }
                if (isWrite(method) && method.getAnnotation(AdminLog.class) == null) {
                    problems.add(id + " 写操作缺 @AdminLog");
                }
            }
        }

        assertThat(endpointCount).isGreaterThanOrEqualTo(50);
        assertThat(problems).as("端点注解问题").isEmpty();
    }

    private boolean isMapped(Method method) {
        return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(DeleteMapping.class);
    }

    private boolean isWrite(Method method) {
        return method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private List<Class<?>> scanAdminControllers() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        // 用主类定位 target/classes（测试类路径第一个 com/swapops/server 是 test-classes，不能扫）
        URL mainRoot = com.swapops.server.SwapServerApplication.class.getProtectionDomain()
                .getCodeSource().getLocation();
        assertThat(mainRoot).as("主类目录可见（测试依赖 main classes）").isNotNull();
        Path root = Paths.get(mainRoot.toURI());
        List<Class<?>> controllers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(path).toString().replace('\\', '/');
                if (!rel.startsWith("com/swapops/server/")) {
                    continue;
                }
                String className = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                Class<?> candidate;
                try {
                    candidate = Class.forName(className, false, loader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!candidate.isAnnotationPresent(RestController.class)) {
                    continue;
                }
                RequestMapping mapping = candidate.getAnnotation(RequestMapping.class);
                if (mapping != null && mapping.value().length > 0 && mapping.value()[0].startsWith("admin/")) {
                    controllers.add(candidate);
                }
            }
        }
        return controllers;
    }
}
