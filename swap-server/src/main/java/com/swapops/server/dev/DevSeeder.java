package com.swapops.server.dev;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swapops.contract.BatteryStatus;
import com.swapops.contract.CabinetStatus;
import com.swapops.contract.CellStatus;
import com.swapops.server.admin.dao.AdminUserDao;
import com.swapops.server.admin.entity.AdminUserEntity;
import com.swapops.server.admin.enums.AdminRole;
import com.swapops.server.admin.security.AdminSecrets;
import com.swapops.server.device.dao.BatteryDao;
import com.swapops.server.device.dao.CabinetDao;
import com.swapops.server.device.dao.CellDao;
import com.swapops.server.device.entity.BatteryEntity;
import com.swapops.server.device.entity.CabinetEntity;
import com.swapops.server.device.entity.CellEntity;
import com.swapops.server.order.service.AllocationService;
import com.swapops.server.user.dao.PlanDao;
import com.swapops.server.user.dao.SwapUserDao;
import com.swapops.server.user.dao.UserPlanDao;
import com.swapops.server.user.dao.WalletDao;
import com.swapops.server.user.entity.PlanEntity;
import com.swapops.server.user.entity.SwapUserEntity;
import com.swapops.server.user.entity.UserPlanEntity;
import com.swapops.server.user.entity.WalletEntity;
import com.swapops.server.user.enums.PlanType;
import com.swapops.server.user.enums.UserPlanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地联调种子（仅 swap.dev.enabled=true）：站点 + N 柜 × M 仓 + 前 K 仓满电电池。
 * 幂等：存在即跳过；密钥经环境变量注入（不落仓库），与模拟器同一 SWAP_DEV_SECRET。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "swap.dev", name = "enabled", havingValue = "true")
public class DevSeeder implements ApplicationRunner {

    private static final String SECRET_PATTERN = "^[0-9a-f]{32}$";

    /** 联调用户数（并发剧本需要 ≥20 个独立用户） */
    private static final int DEV_USER_COUNT = 25;

    private final DevProperties devProperties;
    private final CabinetDao cabinetDao;
    private final CellDao cellDao;
    private final BatteryDao batteryDao;
    private final SwapUserDao swapUserDao;
    private final WalletDao walletDao;
    private final PlanDao planDao;
    private final UserPlanDao userPlanDao;
    private final AllocationService allocationService;
    private final JdbcTemplate jdbcTemplate;
    private final AdminUserDao adminUserDao;
    private final com.swapops.server.user.service.CouponService couponService;
    private final com.swapops.server.settlement.service.AgentService agentService;

    public DevSeeder(DevProperties devProperties, CabinetDao cabinetDao, CellDao cellDao,
                     BatteryDao batteryDao, SwapUserDao swapUserDao, WalletDao walletDao,
                     PlanDao planDao, UserPlanDao userPlanDao, AllocationService allocationService,
                     JdbcTemplate jdbcTemplate, AdminUserDao adminUserDao,
                     com.swapops.server.user.service.CouponService couponService,
                     com.swapops.server.settlement.service.AgentService agentService) {
        this.devProperties = devProperties;
        this.cabinetDao = cabinetDao;
        this.cellDao = cellDao;
        this.batteryDao = batteryDao;
        this.swapUserDao = swapUserDao;
        this.walletDao = walletDao;
        this.planDao = planDao;
        this.userPlanDao = userPlanDao;
        this.allocationService = allocationService;
        this.jdbcTemplate = jdbcTemplate;
        this.adminUserDao = adminUserDao;
        this.couponService = couponService;
        this.agentService = agentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String secret = devProperties.getSecret();
        if (secret == null || !secret.matches(SECRET_PATTERN)) {
            throw new IllegalStateException("swap.dev.secret 必须为 32hex（经环境变量 SWAP_DEV_SECRET 注入）");
        }
        Long stationId = ensureStation();
        // S4.2：种子站坐标（调拨距离用；缺失才补，不覆盖人工维护值）
        jdbcTemplate.update("UPDATE station SET latitude = COALESCE(latitude, ?), "
                + "longitude = COALESCE(longitude, ?) WHERE id = ?", 30.2741, 120.1551, stationId);
        long now = System.currentTimeMillis();
        int cells = devProperties.getCellsPerCabinet();
        for (int i = 1; i <= devProperties.getCabinets(); i++) {
            String cabinetNo = String.format("SWAP-C-%03d", i);
            CabinetEntity cabinet = cabinetDao.selectOne(new LambdaQueryWrapper<CabinetEntity>()
                    .eq(CabinetEntity::getCabinetNo, cabinetNo));
            if (cabinet == null) {
                cabinet = new CabinetEntity();
                cabinet.setCabinetNo(cabinetNo);
                cabinet.setStationId(stationId);
                cabinet.setCellCount(cells);
                cabinet.setStatus(CabinetStatus.ONLINE.getCode());
                cabinet.setSecret(secret);
                cabinet.setCreateTime(now);
                cabinet.setUpdateTime(now);
                cabinetDao.insert(cabinet);
            }
            for (int j = 1; j <= cells; j++) {
                boolean full = j <= devProperties.getFullCells();
                CellEntity cell = cellDao.selectOne(new LambdaQueryWrapper<CellEntity>()
                        .eq(CellEntity::getCabinetId, cabinet.getId())
                        .eq(CellEntity::getCellNo, j));
                if (cell == null) {
                    cell = new CellEntity();
                    cell.setCabinetId(cabinet.getId());
                    cell.setCellNo(j);
                    cell.setStatus(full ? CellStatus.OCCUPIED.getCode() : CellStatus.EMPTY.getCode());
                    cell.setUpdateTime(now);
                    cellDao.insert(cell);
                }
                if (full) {
                    // 确定性编号：与模拟器同一公式 (柜序-1) × 仓数 + 仓号；存在即复用（幂等/自愈）
                    String batteryNo = String.format("BAT-%04d", (i - 1) * cells + j);
                    BatteryEntity battery = batteryDao.selectOne(new LambdaQueryWrapper<BatteryEntity>()
                            .eq(BatteryEntity::getBatteryNo, batteryNo));
                    if (battery == null) {
                        battery = new BatteryEntity();
                        battery.setBatteryNo(batteryNo);
                        battery.setModel("48V24Ah");
                        battery.setStatus(BatteryStatus.FULL.getCode());
                        battery.setSoc(100);
                        battery.setSoh(100);
                        battery.setCycleCount(0);
                        battery.setCellId(cell.getId());
                        battery.setUpdateTime(now);
                        batteryDao.insert(battery);
                    }
                    if (cell.getBatteryId() == null) {
                        cell.setBatteryId(battery.getId());
                        cellDao.updateById(cell);
                    }
                }
            }
            log.info("联调种子就绪 cabinetNo={} cells={} fullCells={}", cabinetNo, cells,
                    devProperties.getFullCells());
        }
        seedUsersAndPlans();
        seedAdminUser();
        seedCoupons();
        seedSettlement();
        // 事件/种子可能先于启动重建发生：全量重建可分配集合（与 DB 真值对齐）
        allocationService.rebuildFromDb();
    }

    /**
     * 联调用户与套餐（幂等）：25 个用户 + 钱包 + 两张套餐模板 + 每人一张次卡（剩 5 次）。
     * 用户 1 押金为 0（供剧本验证"首借缴押金"链路），其余用户押金 9900 已缴。
     */
    private void seedUsersAndPlans() {
        long now = System.currentTimeMillis();
        PlanEntity timesPlan = ensurePlan("次卡10次", PlanType.TIMES.name(), 3000, 10, null, null, now);
        ensurePlan("月卡30天", PlanType.MONTHLY.name(), 9900, null, 30, 3, now);
        for (int i = 1; i <= DEV_USER_COUNT; i++) {
            String phone = String.format("138%08d", i);
            SwapUserEntity user = swapUserDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                    .eq(SwapUserEntity::getPhone, phone));
            if (user == null) {
                user = new SwapUserEntity();
                user.setPhone(phone);
                user.setName("联调用户" + i);
                user.setStatus(1);
                user.setCreateTime(now);
                user.setUpdateTime(now);
                swapUserDao.insert(user);
            }
            if (walletDao.selectOne(new LambdaQueryWrapper<WalletEntity>()
                    .eq(WalletEntity::getUserId, user.getId())) == null) {
                WalletEntity wallet = new WalletEntity();
                wallet.setUserId(user.getId());
                wallet.setBalanceFen(20000);
                wallet.setDepositFen(i == 1 ? 0 : 9900);
                wallet.setUpdateTime(now);
                walletDao.insert(wallet);
            }
            Long planCount = userPlanDao.selectCount(new LambdaQueryWrapper<UserPlanEntity>()
                    .eq(UserPlanEntity::getUserId, user.getId())
                    .eq(UserPlanEntity::getPlanId, timesPlan.getId()));
            if (planCount == null || planCount == 0) {
                UserPlanEntity userPlan = new UserPlanEntity();
                userPlan.setUserId(user.getId());
                userPlan.setPlanId(timesPlan.getId());
                userPlan.setStartTime(now);
                userPlan.setEndTime(now + 365L * 24 * 3600 * 1000);
                userPlan.setRemainingTimes(5);
                userPlan.setStatus(UserPlanStatus.ACTIVE.getCode());
                userPlan.setCreateTime(now);
                userPlan.setUpdateTime(now);
                userPlanDao.insert(userPlan);
            }
        }
        log.info("联调用户种子就绪 users={}（用户1 押金0，其余押金9900；每人次卡剩5次）", DEV_USER_COUNT);
    }

    /** S7 WP-D：新客券模板 + 给前 3 个联调用户发券（幂等：模板同名校验 + 每人限领跳过） */
    private void seedCoupons() {
        try {
            com.swapops.server.user.entity.CouponTemplateEntity template =
                    couponService.listTemplates().stream()
                            .filter(t -> "新客立减1元".equals(t.getName()))
                            .findFirst().orElse(null);
            if (template == null) {
                template = couponService.createTemplate("新客立减1元", 100, 100, 1000, 1, 365);
            }
            java.util.List<Long> userIds = new java.util.ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                SwapUserEntity user = swapUserDao.selectOne(new LambdaQueryWrapper<SwapUserEntity>()
                        .eq(SwapUserEntity::getPhone, String.format("138%08d", i)));
                if (user != null) {
                    userIds.add(user.getId());
                }
            }
            if (!userIds.isEmpty()) {
                couponService.grant(template.getId(), userIds);
            }
            log.info("券种子就绪 templateId={} users={}", template.getId(), userIds.size());
        } catch (Exception e) {
            log.warn("券种子失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /**
     * S7 WP-B：2 个代理商 + 2 个归属站点（ST-002/ST-003），并把 SWAP-C-009/C-010 划入；
     * ST-001 保持直营。幂等：代理/站点存在即跳过、柜归属按名更新。
     */
    private void seedSettlement() {
        try {
            com.swapops.server.settlement.entity.AgentEntity agent1 =
                    ensureAgent("AG001", "示例代理商一", 6000);
            com.swapops.server.settlement.entity.AgentEntity agent2 =
                    ensureAgent("AG002", "示例代理商二", 8000);
            Long station2 = ensureStation("ST-002", "代理站点二", agent1.getId());
            Long station3 = ensureStation("ST-003", "代理站点三", agent2.getId());
            bindCabinet("SWAP-C-009", station2);
            bindCabinet("SWAP-C-010", station3);
            log.info("分账种子就绪 agents=2 stations=ST-002/ST-003 cabinets=C-009/C-010");
        } catch (Exception e) {
            log.warn("分账种子失败（不阻断启动）: {}", e.getMessage());
        }
    }

    private com.swapops.server.settlement.entity.AgentEntity ensureAgent(String no, String name, int shareBp) {
        var existing = agentService.list().stream().filter(a -> no.equals(a.getAgentNo())).findFirst().orElse(null);
        return existing != null ? existing : agentService.create(no, name, null, shareBp, "MONTHLY");
    }

    private Long ensureStation(String stationNo, String name, Long agentId) {
        java.util.List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM station WHERE station_no = ?", Long.class, stationNo);
        if (!ids.isEmpty()) {
            jdbcTemplate.update("UPDATE station SET agent_id = ? WHERE id = ?", agentId, ids.get(0));
            return ids.get(0);
        }
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO station(station_no, name, address, status, agent_id, create_time, update_time) "
                + "VALUES (?, ?, '联调', 1, ?, ?, ?)", stationNo, name, agentId, now, now);
        return jdbcTemplate.queryForList("SELECT id FROM station WHERE station_no = ?", Long.class, stationNo).get(0);
    }

    private void bindCabinet(String cabinetNo, Long stationId) {
        jdbcTemplate.update("UPDATE cabinet SET station_id = ? WHERE cabinet_no = ?", stationId, cabinetNo);
    }

    /** S7 WP-A：管理员引导账号（密码 env 注入；已存在跳过；未配置密码则跳过） */
    private void seedAdminUser() {
        String password = devProperties.getAdminBootstrapPassword();
        if (password == null || password.isBlank()) {
            log.info("未配置 SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD，跳过管理员种子（静态 token 仍可用）");
            return;
        }
        if (password.length() < 8) {
            log.warn("管理员引导密码过短（<8），跳过种子");
            return;
        }
        AdminUserEntity existing = adminUserDao.selectOne(new LambdaQueryWrapper<AdminUserEntity>()
                .eq(AdminUserEntity::getUsername, "admin"));
        if (existing != null) {
            log.info("管理员账号已存在，跳过种子 username=admin");
            return;
        }
        long now = System.currentTimeMillis();
        AdminUserEntity admin = new AdminUserEntity();
        admin.setUsername("admin");
        admin.setPasswordHash(AdminSecrets.hashPassword(password));
        admin.setRealName("引导管理员");
        admin.setRole(AdminRole.SUPER.name());
        admin.setStatus(1);
        admin.setCreateTime(now);
        admin.setUpdateTime(now);
        adminUserDao.insert(admin);
        log.info("管理员种子就绪 username=admin role=SUPER（密码 env 注入，不落日志）");
    }

    private PlanEntity ensurePlan(String name, String type, int priceFen, Integer totalTimes,
                                  Integer durationDays, Integer dailyLimit, long now) {
        PlanEntity plan = planDao.selectOne(new LambdaQueryWrapper<PlanEntity>()
                .eq(PlanEntity::getName, name));
        if (plan != null) {
            return plan;
        }
        plan = new PlanEntity();
        plan.setName(name);
        plan.setPlanType(type);
        plan.setPriceFen(priceFen);
        plan.setTotalTimes(totalTimes);
        plan.setDurationDays(durationDays);
        plan.setDailyLimitTimes(dailyLimit);
        plan.setStatus(1);
        plan.setCreateTime(now);
        planDao.insert(plan);
        return plan;
    }

    private Long ensureStation() {
        java.util.List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM station WHERE station_no = 'ST-001'", Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO station(station_no, name, address, status, create_time, update_time) "
                + "VALUES('ST-001', '示范站点', '本地联调', 1, ?, ?)", now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM station WHERE station_no = 'ST-001'", Long.class);
    }
}
