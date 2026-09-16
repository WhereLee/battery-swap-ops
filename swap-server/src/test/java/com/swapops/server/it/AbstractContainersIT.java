package com.swapops.server.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * 集成测试基座（P0-1）：MySQL 8 + Redis 7 容器（镜像/参数与 docker-compose.middleware.yml 一致），
 * 全量 db/*.sql 迁移经容器 initdb 目录由 mysql 客户端执行（含 DELIMITER 存储过程），
 * 应用以 swap.dev.enabled=true 启动（DevSeeder 提供确定性夹具：4 柜 × 12 仓、前 6 仓满电、
 * 25 个联调用户含套餐/钱包）。
 *
 * <p>运行纪律：`*IT` 由 failsafe 在 verify 阶段执行（本地 Windows 无 Docker：`mvn test` 不受影响，
 * 跑 IT 需 Docker，跳过用 `-DskipITs`）；CI ubuntu-latest 自带 Docker，`mvn verify` 自动带上。</p>
 */
@SpringBootTest(properties = {
        "swap.pay.secret=0123456789abcdef0123456789abcdef",
        "swap.admin.token=fedcba9876543210fedcba9876543210",
        "swap.id.worker-id=0",
        "swap.device.mq.enabled=false",
        "swap.ratelimit.enabled=false",
        "swap.dev.enabled=true",
        "swap.dev.secret=00001111222233334444555566667777",
        "swap.dev.cabinets=4",
        "swap.dev.cells-per-cabinet=12",
        "swap.dev.full-cells=6",
        "spring.main.banner-mode=off"
})
public abstract class AbstractContainersIT {

    protected static final MySQLContainer<?> MYSQL;

    protected static final GenericContainer<?> REDIS;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("swap_ops")
                .withUsername("root")
                .withPassword("it-root")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("characterEncoding", "utf8")
                .withUrlParam("serverTimezone", "Asia/Shanghai");
        copyDbScripts(MYSQL);
        MYSQL.start();
        REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
        REDIS.start();
    }

    /** db/*.sql 拷入容器 initdb 目录：启动时由 mysql 客户端按名序执行（DELIMITER 过程可解析） */
    private static void copyDbScripts(MySQLContainer<?> container) {
        Path dbDir = Paths.get("..", "db").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.list(dbDir)) {
            List<Path> scripts = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            for (Path script : scripts) {
                container.withCopyFileToContainer(MountableFile.forHostPath(script),
                        "/docker-entrypoint-initdb.d/" + script.getFileName());
            }
        } catch (IOException e) {
            throw new IllegalStateException("db 迁移脚本目录不可读: " + dbDir, e);
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
