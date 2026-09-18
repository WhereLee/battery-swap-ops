package com.swapops.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.swapops.server.common.utils.PageParams;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（S8 批次30 修复）。
 *
 * <p><b>为什么必须有这个类</b>：{@code selectPage} 只声明"分页意图"，真正的 {@code LIMIT} 与
 * {@code COUNT} 由 {@link PaginationInnerInterceptor} 在 SQL 执行期改写注入。缺该拦截器时
 * {@code selectPage} <b>不抛错</b>，而是静默退化为全表查询且 {@code total} 恒为 0——
 * 本项目 10 处分页调用（订单 / 资产×4 / 工单 / 调拨 / 建议单 / 审计日志 / 用户 / 告警视图）
 * 曾长期如此，直到前端管理台首次实机联调才暴露（limit=5 实际返回 3854 行）。
 * 详见 {@code document/pitfalls/mp-pagination-interceptor-missing.md}。
 *
 * <p>拦截器顺序按官方建议：改写 SQL 的分页在前，纯校验型的防全表写在后。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页 + 防全表写拦截器。
     *
     * <p>{@code maxLimit} 是第二道防线：{@link PageParams#limit} 已在应用层钳制，但
     * {@code SwapOrderService} / {@code AssetAdminService} 是直接 {@code new Page<>(pageNum, size)}
     * 绕过它的，只有拦截器层能兜住"翻大页拖库"面。
     *
     * <p>{@code overflow=false}：页码越界返回空列表，而不是静默回到首页——否则前端翻页会
     * 反复看到第一页数据却以为翻到了末页。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) PageParams.MAX_LIMIT);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 已核实全仓 update/delete 均带 where 条件（不存在合法的全表写），故本拦截器只拦事故不拦业务
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
