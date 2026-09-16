package com.swapops.server.admin.config;

import com.swapops.server.admin.security.AdminAuthFilter;
import com.swapops.server.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * 管理面安全链（S7 WP-A，参照壳子 SecurityConfig 的 @EnableMethodSecurity + @PreAuthorize 模型）：
 * securityMatcher 收敛为 /admin/** 与 /actuator/**（P1-6：actuator 除 health/info 外与 admin 同级鉴权，
 * prometheus 抓取携 X-Admin-Token）；设备/用户/dev 路径完全不经此链（零影响）。
 * 认证：AdminAuthFilter（会话 token / break-glass 静态 token）；授权：@PreAuthorize 权限码。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AdminSecurityConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminAuthFilter adminAuthFilter)
            throws Exception {
        http
                .securityMatcher("/admin/**", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/admin/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeJson(response, 401, "管理端未认证"))
                        .accessDeniedHandler((request, response, ex) -> writeJson(response, 403, "无权限执行该操作")))
                .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().print(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
