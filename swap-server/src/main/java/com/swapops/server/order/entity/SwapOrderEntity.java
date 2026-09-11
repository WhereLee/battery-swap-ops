package com.swapops.server.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 换电订单（事件驱动状态机；active_user_key 生成列保证"一人一活跃单"）。
 */
@Data
@TableName("swap_order")
public class SwapOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** SWAP / TAKE / RETURN */
    private String orderType;

    private Long userId;

    private Long stationId;

    private Long cabinetId;

    private Long cellId;

    private Long takeBatteryId;

    private Long returnBatteryId;

    private Long userPlanId;

    /** OrderStatus 码值 */
    private Integer status;

    private Integer feeFen;

    private String payType;

    private Long openCommandSeq;

    /** 下单幂等键（唯一；同 key 重放返回同单） */
    private String idemKey;

    /** 预占过期时间（毫秒；扫描任务据此关闭超时单） */
    private Long preemptExpireTime;

    private Long createTime;

    private Long openTime;

    private Long takeTime;

    private Long returnTime;

    private Long completeTime;

    private Long cancelTime;

    /** 关闭原因（TIMEOUT/CANCEL/SEND_FAILED/...） */
    private String closeReason;

    private Long updateTime;
}
