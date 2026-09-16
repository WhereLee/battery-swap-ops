package com.swapops.server.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.swapops.server.admin.dao.AdminOpLogDao;
import com.swapops.server.admin.dao.AdminUserDao;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.asset.dao.StationDao;
import com.swapops.server.asset.entity.StationEntity;
import com.swapops.server.common.RRException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员账号管理单测（P1-8 扩展）：数据范围校验（ALL 缺省 / STATION 站点编号必填且存在）、
 * 归一化落库、非法输入拒绝。
 */
@DisplayName("管理员账号（数据范围）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountServiceTest {

    @Mock
    private AdminUserDao adminUserDao;
    @Mock
    private AdminOpLogDao adminOpLogDao;
    @Mock
    private StationDao stationDao;

    private AdminAccountService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AdminUserEntity.class);
        TableInfoHelper.initTableInfo(assistant, StationEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(adminUserDao, adminOpLogDao, stationDao);
        when(adminUserDao.selectOne(any())).thenReturn(null);
    }

    private StationEntity station(long id, String no) {
        StationEntity station = new StationEntity();
        station.setId(id);
        station.setStationNo(no);
        return station;
    }

    @Test
    @DisplayName("缺省数据范围：ALL 且不查站点表")
    void 缺省ALL() {
        AdminUserEntity created = service.create("ops-new", "password123", "新运营", "OPS");

        assertThat(created.getDataScope()).isEqualTo("ALL");
        assertThat(created.getScopeStationNos()).isNull();
        verify(stationDao, never()).selectList(any());
    }

    @Test
    @DisplayName("STATION 范围：站点编号 trim/去重/排序保留，落库归一化串")
    void STATION归一化() {
        when(stationDao.selectList(any())).thenReturn(List.of(station(2L, "ST-002"), station(3L, "ST-003")));

        AdminUserEntity created = service.create("ops-st", "password123", "站点运营", "OPS",
                "STATION", " ST-003 , ST-002 ,ST-003 ");

        assertThat(created.getDataScope()).isEqualTo("STATION");
        assertThat(created.getScopeStationNos()).isEqualTo("ST-003,ST-002");
    }

    @Test
    @DisplayName("STATION 缺站点编号：拒绝（防静默无数据）")
    void STATION缺编号拒绝() {
        assertThatThrownBy(() -> service.create("ops-st", "password123", null, "OPS", "STATION", "  "))
                .isInstanceOf(RRException.class).hasMessageContaining("必须配置站点编号");
    }

    @Test
    @DisplayName("STATION 站点不存在：拒绝并点名缺失编号")
    void STATION站点不存在拒绝() {
        when(stationDao.selectList(any())).thenReturn(List.of(station(2L, "ST-002")));

        assertThatThrownBy(() -> service.create("ops-st", "password123", null, "OPS",
                "STATION", "ST-002,ST-999"))
                .isInstanceOf(RRException.class).hasMessageContaining("ST-999");
    }

    @Test
    @DisplayName("非法数据范围值：拒绝")
    void 非法范围拒绝() {
        assertThatThrownBy(() -> service.create("ops-x", "password123", null, "OPS", "REGION", null))
                .isInstanceOf(RRException.class).hasMessageContaining("数据范围");
    }

    @Test
    @DisplayName("小写 station 归一：大小写不敏感匹配 ALL/STATION")
    void 范围大小写不敏感() {
        when(stationDao.selectList(any())).thenReturn(List.of(station(2L, "ST-002")));

        AdminUserEntity created = service.create("ops-low", "password123", null, "OPS", "station", "ST-002");

        assertThat(created.getDataScope()).isEqualTo("STATION");
    }
}
