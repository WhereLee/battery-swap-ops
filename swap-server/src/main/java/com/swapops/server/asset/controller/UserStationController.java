package com.swapops.server.asset.controller;

import com.swapops.server.asset.service.AssetAdminService;
import com.swapops.server.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户端：站点列表（含可换/可还容量）。
 */
@RestController
@RequestMapping("user")
public class UserStationController {

    private final AssetAdminService assetAdminService;

    public UserStationController(AssetAdminService assetAdminService) {
        this.assetAdminService = assetAdminService;
    }

    @GetMapping("/stations")
    public Result<List<Map<String, Object>>> stations() {
        return Result.ok(assetAdminService.listUserStations());
    }
}
