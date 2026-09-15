package com.swapops.server.order.controller;

import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.order.entity.ArrearsRecordEntity;
import com.swapops.server.order.service.ArrearsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户端欠费（S7 WP-D）：未结清列表 + 余额补缴（结清后自动关告警）。
 */
@RestController
@RequestMapping("user")
public class UserArrearsController {

    private final ArrearsService arrearsService;

    public UserArrearsController(ArrearsService arrearsService) {
        this.arrearsService = arrearsService;
    }

    @GetMapping("/arrears")
    public Result<Map<String, Object>> list() {
        List<ArrearsRecordEntity> records = arrearsService.listOpen(UserContext.require());
        int total = records.stream()
                .filter(record -> record.getStatus() != null && record.getStatus() == 1)
                .mapToInt(record -> (record.getAmountFen() == null ? 0 : record.getAmountFen())
                        - (record.getSettledFen() == null ? 0 : record.getSettledFen()))
                .sum();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("totalOpenFen", total);
        view.put("records", records);
        return Result.ok(view);
    }

    @PostMapping("/arrears/{id}/pay")
    public Result<ArrearsRecordEntity> pay(@PathVariable Long id) {
        return Result.ok(arrearsService.pay(UserContext.require(), id));
    }
}
