package com.swapops.server.device.config;

import com.swapops.contract.DeviceSignature;
import com.swapops.contract.EventType;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.form.DeviceEventForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 设备通道鉴权单测：正例、错签、未登记、未知事件类型（400）。
 */
@DisplayName("设备通道鉴权（per-柜 HMAC）")
@ExtendWith(MockitoExtension.class)
class DeviceChannelAuthenticatorTest {

    private static final String SECRET = "aabbccddeeff00112233445566778899";

    @Mock
    private CabinetDao cabinetDao;
    @InjectMocks
    private DeviceChannelAuthenticator authenticator;

    private DeviceEventForm form;

    @BeforeEach
    void setUp() {
        form = new DeviceEventForm();
        form.setCabinetNo("SWAP-C-001");
        form.setEventType(EventType.BATTERY_OUT.name());
        form.setCellNo(3);
        form.setBatteryNo("BAT-0001");
        form.setBootId("boot-1");
        form.setEventSeq(43L);
    }

    private void stubCabinet(String secret) {
        CabinetEntity cabinet = new CabinetEntity();
        cabinet.setCabinetNo("SWAP-C-001");
        cabinet.setSecret(secret);
        when(cabinetDao.selectOne(any())).thenReturn(cabinet);
    }

    @Test
    @DisplayName("正例：正确签名通过")
    void 正例通过() {
        stubCabinet(SECRET);
        String sign = DeviceSignature.sign(SECRET, DeviceSignature.canonicalEvent(
                "SWAP-C-001", EventType.BATTERY_OUT, 3, "BAT-0001", "boot-1", 43L));

        assertThatCode(() -> authenticator.authenticateEvent(form, sign)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("错签：401")
    void 错签_401() {
        stubCabinet(SECRET);
        assertThatThrownBy(() -> authenticator.authenticateEvent(form, "deadbeef"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("签名无效");
    }

    @Test
    @DisplayName("未登记：401")
    void 未登记_401() {
        when(cabinetDao.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> authenticator.authenticateEvent(form, "x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    @DisplayName("未知事件类型：400（协议垃圾）")
    void 未知事件类型_400() {
        stubCabinet(SECRET);
        form.setEventType("NOPE");
        assertThatThrownBy(() -> authenticator.authenticateEvent(form, "x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未知事件类型");
    }
}
