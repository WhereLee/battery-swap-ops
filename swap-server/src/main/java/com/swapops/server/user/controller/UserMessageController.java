package com.swapops.server.user.controller;

import com.swapops.server.common.Result;
import com.swapops.server.common.web.UserContext;
import com.swapops.server.user.entity.UserMessageEntity;
import com.swapops.server.user.service.UserMessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端站内信（S7 WP-D）：列表 + 标记已读。 */
@RestController
@RequestMapping("user")
public class UserMessageController {

    private final UserMessageService messageService;

    public UserMessageController(UserMessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/messages")
    public Result<List<UserMessageEntity>> list(@RequestParam(required = false) Boolean unreadOnly,
                                                @RequestParam(required = false) Integer limit) {
        return Result.ok(messageService.list(UserContext.require(), unreadOnly, limit));
    }

    @PostMapping("/messages/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        messageService.markRead(UserContext.require(), id);
        return Result.ok();
    }
}
