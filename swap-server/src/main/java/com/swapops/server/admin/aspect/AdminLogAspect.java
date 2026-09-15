package com.swapops.server.admin.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapops.server.admin.annotation.AdminLog;
import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.entity.AdminOpLogEntity;
import com.swapops.server.admin.security.AdminContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * 管理操作审计切面（S7 WP-A，参照壳子 SysLogAspect）：
 * 入参 JSON 化后按敏感字段脱敏并截断；审计写入失败不影响业务（只告警）。
 */
@Slf4j
@Aspect
@Component
public class AdminLogAspect {

    private static final int MAX_FIELD = 1000;
    private static final int MAX_ERROR = 255;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(\"(?:password|sign|token|secret|passwordHash)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private final AdminOpLogDao adminOpLogDao;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminLogAspect(AdminOpLogDao adminOpLogDao) {
        this.adminOpLogDao = adminOpLogDao;
    }

    @Around("@annotation(adminLog)")
    public Object around(ProceedingJoinPoint point, AdminLog adminLog) throws Throwable {
        long begin = System.currentTimeMillis();
        int resultCode = 0;
        String error = null;
        try {
            return point.proceed();
        } catch (Throwable e) {
            resultCode = 1;
            error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), MAX_ERROR);
            throw e;
        } finally {
            try {
                writeLog(point, adminLog, resultCode, error, System.currentTimeMillis() - begin);
            } catch (Exception e) {
                log.warn("[admin-audit] 审计写入失败 action={} cause={}", adminLog.value(), e.getMessage());
            }
        }
    }

    private void writeLog(ProceedingJoinPoint point, AdminLog adminLog, int resultCode, String error,
                          long durationMs) {
        AdminOpLogEntity entity = new AdminOpLogEntity();
        AdminContext.Principal principal = AdminContext.current();
        entity.setAdminId(principal == null ? null : principal.adminId());
        entity.setUsername(principal == null ? "system" : principal.username());
        entity.setAction(adminLog.value());
        HttpServletRequest request = currentRequest();
        entity.setMethod(request == null ? "-" : request.getMethod());
        entity.setUri(request == null ? point.getSignature().toShortString() : request.getRequestURI());
        entity.setParamsJson(describeArgs(point.getArgs()));
        entity.setResultCode(resultCode);
        entity.setError(error);
        entity.setDurationMs(durationMs);
        entity.setIp(request == null ? null : request.getRemoteAddr());
        entity.setCreateTime(System.currentTimeMillis());
        adminOpLogDao.insert(entity);
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** 入参概览（脱敏 + 截断；序列化失败退化为类型名列表） */
    private String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(args);
            return truncate(SENSITIVE.matcher(json).replaceAll("$1\"***\""), MAX_FIELD);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(arg == null ? "null" : arg.getClass().getSimpleName());
            }
            return truncate(sb.toString(), MAX_FIELD);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
