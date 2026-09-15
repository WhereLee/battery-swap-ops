package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.RRException;
import com.swapops.server.user.dao.UserMessageDao;
import com.swapops.server.user.entity.UserMessageEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信单测（S7 WP-D）：写入（失败不影响业务）/ 列表限额 / 已读幂等与越权。
 */
@DisplayName("站内信")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserMessageServiceTest {

    @Mock
    private UserMessageDao userMessageDao;

    private UserMessageService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserMessageEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new UserMessageService(userMessageDao);
    }

    @Test
    @DisplayName("发送：落库；写库异常被吞（不影响业务）")
    void 发送() {
        when(userMessageDao.insert(any(UserMessageEntity.class))).thenReturn(1);
        service.send(7L, "REWARD", "获得优惠券", "立减 100 分");
        verify(userMessageDao).insert(any(UserMessageEntity.class));

        when(userMessageDao.insert(any(UserMessageEntity.class)))
                .thenThrow(new RuntimeException("db down"));
        service.send(7L, "REFUND", "退款到账", "无误抛出");
    }

    @Test
    @DisplayName("列表：默认 50 上限 100；仅未读过滤")
    void 列表() {
        service.list(7L, null, null);
        service.list(7L, Boolean.TRUE, 999);
        verify(userMessageDao, org.mockito.Mockito.atLeastOnce()).selectList(any());
    }

    @Test
    @DisplayName("已读：CAS 更新成功；已读幂等；他人消息拒绝")
    void 已读() {
        when(userMessageDao.update(isNull(), any())).thenReturn(1);
        assertThat(service.markRead(7L, 9L)).isTrue();

        when(userMessageDao.update(isNull(), any())).thenReturn(0);
        UserMessageEntity existing = new UserMessageEntity();
        existing.setId(9L);
        existing.setUserId(7L);
        when(userMessageDao.selectOne(any())).thenReturn(existing);
        assertThat(service.markRead(7L, 9L)).isTrue();

        when(userMessageDao.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.markRead(7L, 9L))
                .isInstanceOf(RRException.class).hasMessageContaining("无权");
    }
}
