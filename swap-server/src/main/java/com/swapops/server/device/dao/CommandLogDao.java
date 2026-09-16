package com.swapops.server.device.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.swapops.server.device.entity.CommandLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CommandLogDao extends BaseMapper<CommandLogEntity> {

    /** 柜内指令序号历史最大值（P1-10 混沌修复：seq 生成兜底——Redis 数据回退时不得低于 DB 真值） */
    @Select("SELECT COALESCE(MAX(command_seq), 0) FROM command_log WHERE cabinet_no = #{cabinetNo}")
    Long selectMaxSeq(@Param("cabinetNo") String cabinetNo);
}
