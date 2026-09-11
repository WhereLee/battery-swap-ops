package com.swapops.server.common.ratelimit;

import com.swapops.server.common.web.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 令牌桶限流切面（S3.8 WP2，标准实现）：
 * <ul>
 *   <li>桶状态在 Redis，Lua 原子"取时间/补令牌/扣减/续期"——多实例共享、无竞态超发；</li>
 *   <li>时间以 Redis TIME 为准（跨实例时钟不一致不影响判定）；</li>
 *   <li>维度解析：GLOBAL / USER(UserContext) / IP(XFF 优先) / API(方法签名)，支持 SpEL 附加键；</li>
 *   <li>拒绝：429 + Retry-After（由 RRExceptionHandler 映射）；</li>
 *   <li>Redis 故障 fail-open（放行 + 告警）——限流只降体验不阻业务。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    /** 令牌桶 Lua：HMGET tokens/ts → 按 redis TIME 补令牌 → 扣减 → 写回并设过期 */
    private static final String LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
                tokens = capacity
                ts = now
            end
            local delta = math.max(0, now - ts) / 1000
            tokens = math.min(capacity, tokens + delta * rate)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            local ttl = math.ceil(capacity / rate * 2)
            if ttl < 1 then ttl = 1 end
            redis.call('EXPIRE', key, ttl)
            return {allowed, math.floor(tokens)}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, LongAdder> allowedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> deniedCounters = new ConcurrentHashMap<>();

    public RateLimitAspect(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }
        String bucketKey = bucketKey(pjp, rateLimit);
        boolean acquired;
        try {
            acquired = acquire(bucketKey, rateLimit.permits(), rateLimit.windowSeconds());
        } catch (RuntimeException e) {
            // fail-open：限流是保护，不是正确性依赖
            log.warn("[ratelimit] Redis 异常放行 limiter={} cause={}", rateLimit.name(), e.getMessage());
            return pjp.proceed();
        }
        if (!acquired) {
            deniedCounters.computeIfAbsent(rateLimit.name(), k -> new LongAdder()).increment();
            long retryAfter = Math.max(1L, (long) Math.ceil(rateLimit.windowSeconds() / rateLimit.permits()));
            log.warn("[ratelimit] 拒绝 limiter={} bucket={} retryAfter={}s",
                    rateLimit.name(), bucketKey, retryAfter);
            throw new RateLimitExceededException(rateLimit.name(), retryAfter);
        }
        allowedCounters.computeIfAbsent(rateLimit.name(), k -> new LongAdder()).increment();
        return pjp.proceed();
    }

    /** 运行指标（管理端可读） */
    public Map<String, Long> stats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isEnabled() ? 1L : 0L);
        TreeSet<String> names = new TreeSet<>();
        names.addAll(allowedCounters.keySet());
        names.addAll(deniedCounters.keySet());
        for (String name : names) {
            LongAdder allowed = allowedCounters.get(name);
            LongAdder denied = deniedCounters.get(name);
            stats.put("allowed:" + name, allowed == null ? 0L : allowed.sum());
            stats.put("denied:" + name, denied == null ? 0L : denied.sum());
        }
        return stats;
    }

    @SuppressWarnings("unchecked")
    private boolean acquire(String bucketKey, double permits, double windowSeconds) {
        double rate = permits / windowSeconds;
        List<Long> result = (List<Long>) redis.execute(SCRIPT, List.of(bucketKey),
                String.valueOf(rate), String.valueOf(permits));
        return result != null && !result.isEmpty() && result.get(0) != null && result.get(0) == 1L;
    }

    String bucketKey(ProceedingJoinPoint pjp, RateLimit rateLimit) {
        String suffix = evaluateKey(pjp, rateLimit.key());
        String base = switch (rateLimit.dimension()) {
            case GLOBAL -> "global";
            case API -> pjp.getSignature().toShortString();
            case USER -> {
                Long userId = UserContext.get();
                yield userId == null ? "anon" : String.valueOf(userId);
            }
            case IP -> clientIp();
        };
        return "swap:ratelimit:" + rateLimit.name() + ":" + (suffix.isBlank() ? base : base + ":" + suffix);
    }

    /** SpEL 附加键（如 #form.phone；参数名需编译期保留，Spring 默认 -parameters 下可用） */
    private String evaluateKey(ProceedingJoinPoint pjp, String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        StandardEvaluationContext context = new MethodBasedEvaluationContext(
                null, signature.getMethod(), pjp.getArgs(), new DefaultParameterNameDiscoverer());
        Object value = expressionParser.parseExpression(expression).getValue(context);
        return value == null ? "" : value.toString();
    }

    private String clientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
