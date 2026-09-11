package com.swapops.server.device.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.contract.CommandAction;
import com.swapops.server.common.RRException;
import com.swapops.server.device.dao.CommandLogDao;
import com.swapops.server.device.entity.CommandLogEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指令流水单测：seq 生成（Redis 不可用快速失败）、CAS 销账/失败。
 */
@DisplayName("指令流水（seq 幂等 + CAS）")
@ExtendWith(MockitoExtension.class)
class CommandLogServiceTest {

    @Mock
    private CommandLogDao commandLogDao;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @InjectMocks
    private CommandLogService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CommandLogEntity.class);
    }

    @Test
    @DisplayName("nextSeq：Redis INCR 原子递增")
    void nextSeq_原子递增() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("swap:cmd-seq:SWAP-C-001")).thenReturn(5L);

        assertThat(service.nextSeq("SWAP-C-001")).isEqualTo(5L);
    }

    @Test
    @DisplayName("nextSeq：Redis 不可用快速失败（宁可不发也不发重）")
    void nextSeq_redis不可用_失败() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.nextSeq("SWAP-C-001"))
                .isInstanceOf(RRException.class)
                .hasMessageContaining("Redis 不可用");
    }

    @Test
    @DisplayName("markArrivedBySeq：PENDING 条件销账命中")
    void markArrived_命中() {
        when(commandLogDao.update(isNull(), any())).thenReturn(1);

        assertThat(service.markArrivedBySeq("SWAP-C-001", 7L, CommandAction.OPEN_CELL)).isTrue();
    }

    @Test
    @DisplayName("markArrivedBySeq：未命中（已闭环/非指令驱动）不报错")
    void markArrived_未命中() {
        when(commandLogDao.update(isNull(), any())).thenReturn(0);

        assertThat(service.markArrivedBySeq("SWAP-C-001", 7L, CommandAction.OPEN_CELL)).isFalse();
    }

    @Test
    @DisplayName("markSendFailed：PENDING→SEND_FAILED 条件更新")
    void markSendFailed_cas() {
        when(commandLogDao.update(isNull(), any())).thenReturn(1);

        service.markSendFailed(100L);

        verify(commandLogDao).update(isNull(), any());
    }

    @Test
    @DisplayName("seedSeqFromDb：Redis 缺失/落后时按 DB 历史最大值对齐（LUA 原子）")
    void seedSeq_db更高_对齐() {
        Map<String, Object> row = new HashMap<>();
        row.put("cabinet_no", "SWAP-C-001");
        row.put("max_seq", 9L);
        when(commandLogDao.selectMaps(any())).thenReturn(List.of(row));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), eq("9"))).thenReturn(1L);

        service.seedSeqFromDb();

        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), eq("9"));
    }

    @Test
    @DisplayName("seedSeqFromDb：对齐失败不阻断启动（尽力恢复）")
    void seedSeq_失败不阻断() {
        when(commandLogDao.selectMaps(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.seedSeqFromDb()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("超时查询与重试分层：findTimeoutPending / markRetried / RETRY_EXCEEDED / SUPERSEDED / 故障全断")
    void 重试分层与故障全断() {
        when(commandLogDao.selectList(any())).thenReturn(List.of());
        assertThat(service.findTimeoutPending(60, 100)).isEmpty();

        when(commandLogDao.update(isNull(), any())).thenReturn(1);
        service.markRetried(100L);
        assertThat(service.markRetryExceeded(100L)).isTrue();
        assertThat(service.markSuperseded(100L)).isTrue();
        assertThat(service.markExecFailedByCabinet("SWAP-C-001")).isEqualTo(1);
    }
}
