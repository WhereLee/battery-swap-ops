package com.swapops.server.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.swapops.server.user.dao.WalletDao;
import com.swapops.server.user.entity.WalletEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 钱包：余额/押金全部走条件 UPDATE（CAS）——并发下不超扣、不透支；
 * 扣款失败由调用方按业务语义处置（拒绝下单/回滚事件事务）。
 */
@Slf4j
@Service
public class WalletService {

    private final WalletDao walletDao;

    public WalletService(WalletDao walletDao) {
        this.walletDao = walletDao;
    }

    public WalletEntity getByUserId(Long userId) {
        return walletDao.selectOne(new LambdaQueryWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId));
    }

    /** 扣余额：仅当余额充足才成功（原子条件更新） */
    public boolean deductBalance(Long userId, int amountFen) {
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId)
                .ge(WalletEntity::getBalanceFen, amountFen)
                .setSql("balance_fen = balance_fen - " + amountFen)
                .set(WalletEntity::getUpdateTime, System.currentTimeMillis()));
        return rows > 0;
    }

    /** 扣押金：仅当押金充足才成功 */
    public boolean deductDeposit(Long userId, int amountFen) {
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId)
                .ge(WalletEntity::getDepositFen, amountFen)
                .setSql("deposit_fen = deposit_fen - " + amountFen)
                .set(WalletEntity::getUpdateTime, System.currentTimeMillis()));
        return rows > 0;
    }

    /** 余额转押金（首借缴纳押金；CAS 余额充足才成功，原子两列更新） */
    public boolean moveBalanceToDeposit(Long userId, int amountFen) {
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId)
                .ge(WalletEntity::getBalanceFen, amountFen)
                .setSql("balance_fen = balance_fen - " + amountFen)
                .setSql("deposit_fen = deposit_fen + " + amountFen)
                .set(WalletEntity::getUpdateTime, System.currentTimeMillis()));
        return rows > 0;
    }

    /**
     * 退还押金：押金转入余额并清零（CAS 按读到的押金值防并发双退）。
     *
     * @return 实退金额（0=无可退）
     */
    public int refundDeposit(Long userId) {
        WalletEntity wallet = getByUserId(userId);
        if (wallet == null || wallet.getDepositFen() == null || wallet.getDepositFen() <= 0) {
            return 0;
        }
        int amount = wallet.getDepositFen();
        int rows = walletDao.update(null, new LambdaUpdateWrapper<WalletEntity>()
                .eq(WalletEntity::getUserId, userId)
                .eq(WalletEntity::getDepositFen, amount)
                .set(WalletEntity::getDepositFen, 0)
                .setSql("balance_fen = balance_fen + " + amount)
                .set(WalletEntity::getUpdateTime, System.currentTimeMillis()));
        if (rows > 0) {
            log.info("押金退还入余额 userId={} amountFen={}", userId, amount);
            return amount;
        }
        return 0;
    }
}
