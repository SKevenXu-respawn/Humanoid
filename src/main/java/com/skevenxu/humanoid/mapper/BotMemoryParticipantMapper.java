package com.skevenxu.humanoid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skevenxu.humanoid.entity.BotMemoryParticipant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BotMemoryParticipantMapper extends BaseMapper<BotMemoryParticipant> {

    @Select("""
            SELECT memory_id FROM bot_memory_participant
            WHERE user_id = #{userId}
            ORDER BY memory_id DESC
            LIMIT #{limit}
            """)
    List<Long> selectMemoryIdsByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
