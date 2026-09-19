package com.swapops.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.server.order.dao.PaymentRecordDao;
import com.swapops.server.order.dao.RefundRecordDao;
import com.swapops.server.order.entity.PaymentRecordEntity;
import com.swapops.server.order.entity.RefundRecordEntity;
import com.swapops.server.order.enums.PaymentType;
import com.swapops.server.order.enums.RefundStatus;
import com.swapops.server.order.service.PaymentRecordService;
import com.swapops.server.order.service.pay.RefundService;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * IT-4 退款资金动作的原子性（批次32，真实 MySQL 事务 + 真实钱包，<b>故障注入</b>）。
 *
 * <p>为什么必须有这条 IT：批次32 发现 P0 缺陷——{@code RefundService.apply} 上的
 * {@code @Transactional} 因<b>同类自调用</b>而静默失效，于是"入余额"先自动提交、"状态 CAS"后做，
 * 两步之间任何失败都留下"钱已出、单仍 WAIT"，而 WAIT 单会被重驱动再次入账
 * ⇒ 同一张退款单向用户退两次钱。这个缺陷在单测（全 Mock，看不到代理）、SpotBugs、
 * 覆盖率三层防线里全都不显形，只有"真事务 + 真钱包 + 在两步之间人为制造失败"能证明修好了。
 *
 * <p>注入点选在加款之后的那个协作方（{@code PaymentRecordService}），并用
 * {@code verify(record)} 先证明"加款那一步确实执行过"——否则"余额没变"可能只是因为压根没走到加款，
 * 断言就成了空转。
 */
@DisplayName("IT-4 退款原子性（故障注入）")
class RefundAtomicityIT extends AbstractContainersIT {

    /** 合成订单号：payment_record / refund_record 无外键（全库零 FOREIGN KEY，已核对 db/*.sql） */
    private static final long SYNTHETIC_ORDER_ID = 987654321L;
    private static final int REFUND_FEN = 500;
    private static final String PHONE = "13800000003";

    @Autowired
    private RefundService refundService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private PaymentRecordDao paymentRecordDao;
    @Autowired
    private RefundRecordDao refundRecordDao;
    @Autowired
    private SwapUserDao userDao;

    /** 故障注入：真实的 payment_record 写入被替换为必然失败（等价于死锁/连接抖动）。 */
    @MockitoBean
    private PaymentRecordService paymentRecordService;

    private SwapUserEntity user() {
        SwapUserEntity user = userDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                .eq(SwapUserEntity::getPhone, PHONE));
        assertThat(user).as("种子用户存在: " + PHONE).isNotNull();
        return user;
    }

    private void seedRefundableCharge(Long userId, long orderId, int amountFen) {
        PaymentRecordEntity charge = new PaymentRecordEntity();
        charge.setUserId(userId);
        charge.setOrderId(orderId);
        charge.setPaymentType(PaymentType.BALANCE_FEE.name());
        charge.setAmountFen(amountFen);
        charge.setChannel("MOCK");
        charge.setTradeNo("IT4-CHARGE-" + orderId);
        charge.setStatus(1);
        charge.setCreateTime(System.currentTimeMillis());
        paymentRecordDao.insert(charge);
    }

    @Test
    @DisplayName("加款后失败必须整体回滚（余额不变、单留 WAIT）；重驱动后恰好入账一次")
    void refundIsAtomicAndRedrivable() {
        SwapUserEntity user = user();
        Long userId = user.getId();
        int balanceBefore = walletService.getByUserId(userId).getBalanceFen();
        seedRefundableCharge(userId, SYNTHETIC_ORDER_ID, REFUND_FEN);
        assertThat(refundService.refundableAmount(SYNTHETIC_ORDER_ID)).isEqualTo(REFUND_FEN);

        // ---------- 1) 注入失败：加款已发生，紧随其后的流水写入抛异常 ----------
        doThrow(new RuntimeException("injected failure between credit and CAS"))
                .when(paymentRecordService).record(any(), any(), any(), anyInt(), anyString());

        RefundRecordEntity pending = refundService.refund(SYNTHETIC_ORDER_ID, userId, REFUND_FEN, "ORDER_EXCEPTION");

        verify(paymentRecordService)
                .record(userId, SYNTHETIC_ORDER_ID, PaymentType.REFUND, REFUND_FEN, "退款:ORDER_EXCEPTION");
        assertThat(walletService.getByUserId(userId).getBalanceFen())
                .as("★ 决定性断言：加款与 CAS 同事务，后一步失败必须连加款一起回滚"
                        + "（修复前 @Transactional 因自调用失效，此处会多出 " + REFUND_FEN + " 分）")
                .isEqualTo(balanceBefore);
        assertThat(pending.getStatus())
                .as("失败后单子停在 WAIT（可被重驱动），而不是 SUCCESS")
                .isEqualTo(RefundStatus.WAIT.name());
        assertThat(refundRecordDao.selectById(pending.getId()))
                .as("WAIT 单本体保留：ops 看得到悬空单，重驱动有据可依")
                .isNotNull();

        // ---------- 2) 重驱动：故障消失后同一张单恰好入账一次 ----------
        reset(paymentRecordService);
        refundService.apply(refundRecordDao.selectById(pending.getId()));

        assertThat(walletService.getByUserId(userId).getBalanceFen())
                .as("重驱动后只增加一次退款额（不是两次）")
                .isEqualTo(balanceBefore + REFUND_FEN);
        assertThat(refundRecordDao.selectById(pending.getId()).getStatus())
                .isEqualTo(RefundStatus.SUCCESS.name());
        // 注意：PaymentRecordService 在本 IT 里被替换成 mock，所以"REFUND 流水行"这一层
        // 不在这里断言（mock 不会真的写库；reset 也不会把真实实现还回来）。
        // 本 IT 守的是"钱包金额"这一资金结果；流水行本身的正确性由 PaymentRecordService
        // 自己的单测与批次31 的列表契约剧本覆盖。宁可少断言，不写会误导人的断言。

        // ---------- 3) 终态幂等：重复 apply 不再入账（CAS 是闸门） ----------
        refundService.apply(refundRecordDao.selectById(pending.getId()));
        assertThat(walletService.getByUserId(userId).getBalanceFen())
                .as("SUCCESS 单重复 apply 不产生第三次入账")
                .isEqualTo(balanceBefore + REFUND_FEN);
    }
}
