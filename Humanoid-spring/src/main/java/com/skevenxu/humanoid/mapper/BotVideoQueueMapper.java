package com.skevenxu.humanoid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BotVideoQueueMapper extends BaseMapper<BotVideoQueue> {

    /** 每个用户取最早的一条待发送视频，按队列顺序排列。 */
    @Select("""
            SELECT v.* FROM bot_video_queue v
            JOIN (
                SELECT user_id, MIN(id) AS min_id
                FROM bot_video_queue
                WHERE status = 'PENDING'
                GROUP BY user_id
            ) t ON v.id = t.min_id
            ORDER BY v.id
            LIMIT #{limit}
            """)
    List<BotVideoQueue> selectNextPerUser(@Param("limit") int limit);
}
