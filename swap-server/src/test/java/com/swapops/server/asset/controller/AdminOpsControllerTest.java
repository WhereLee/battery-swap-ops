package com.swapops.server.asset.controller;

import com.swapops.server.common.Result;
import com.swapops.server.order.service.AllocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 运维端点单测（S5 运维审查补）：重建分配集合委托（Redis 故障恢复入口）。
 */
@DisplayName("运维端点（分配集合重建）")
@ExtendWith(MockitoExtension.class)
class AdminOpsControllerTest {

    @Mock
    private AllocationService allocationService;

    @Test
    @DisplayName("rebuild-alloc：委托全量重建并返回标记")
    void 重建分配集合() {
        AdminOpsController controller = new AdminOpsController(allocationService);

        Result<Map<String, Object>> result = controller.rebuildAlloc();

        verify(allocationService).rebuildFromDb();
        assertThat(result.getData().get("rebuilt")).isEqualTo(true);
    }
}
