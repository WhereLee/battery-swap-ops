package com.swapops.server.settlement.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.common.RRException;
import com.swapops.server.settlement.dao.AgentDao;
import com.swapops.server.settlement.entity.AgentEntity;
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
import static org.mockito.Mockito.when;

/**
 * 代理商管理单测（S7 WP-B）：编号/比例边界/结算口径校验/停用。
 */
@DisplayName("代理商管理")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentServiceTest {

    @Mock
    private AgentDao agentDao;

    private AgentService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentService(agentDao);
    }

    @Test
    @DisplayName("创建校验：编号格式/重复/比例 0~10000/结算口径")
    void 创建校验() {
        assertThatThrownBy(() -> service.create("bad no", "x", null, 0, "MONTHLY"))
                .isInstanceOf(RRException.class).hasMessageContaining("编号");
        when(agentDao.selectOne(any())).thenReturn(new AgentEntity());
        assertThatThrownBy(() -> service.create("AG001", "x", null, 0, "MONTHLY"))
                .isInstanceOf(RRException.class).hasMessageContaining("已存在");
        when(agentDao.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.create("AG001", "x", null, 10001, "MONTHLY"))
                .isInstanceOf(RRException.class).hasMessageContaining("0~10000");
        assertThatThrownBy(() -> service.create("AG001", "x", null, 0, "YEARLY"))
                .isInstanceOf(RRException.class).hasMessageContaining("结算口径");
    }

    @Test
    @DisplayName("创建成功与停用")
    void 创建与停用() {
        when(agentDao.selectOne(any())).thenReturn(null);
        when(agentDao.insert(any(AgentEntity.class))).thenReturn(1);
        AgentEntity agent = service.create("AG001", "示例代理", "138", 6000, "WEEKLY");
        assertThat(agent.getShareBp()).isEqualTo(6000);
        assertThat(agent.getSettlementCycle()).isEqualTo("WEEKLY");

        when(agentDao.selectById(9L)).thenReturn(agent);
        AgentEntity stopped = service.changeStatus(9L, 2);
        assertThat(stopped.getStatus()).isEqualTo(2);
        assertThatThrownBy(() -> service.changeStatus(9L, 5))
                .isInstanceOf(RRException.class).hasMessageContaining("状态可选");
    }
}
