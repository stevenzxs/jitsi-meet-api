package com.jitsimeet.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jitsimeet.api.domain.Meeting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 会议 Mapper，使用 MyBatis Plus 管理 SQLite 中的 meeting 表。
 */
@Mapper
public interface MeetingMapper extends BaseMapper<Meeting> {

    /** Compare-and-swap prevents two concurrent transfers from both succeeding.
     * Update credentials only: stale meeting objects must not overwrite subject/status.
     */
    @Update("UPDATE meeting SET host_key = #{replacement} WHERE room_name = #{room} AND host_key = #{expected} AND status = #{status}")
    int rotateHostKey(@Param("room") String room, @Param("expected") String expected,
                      @Param("replacement") String replacement, @Param("status") String status);

    @Update("UPDATE meeting SET subject = #{subject} WHERE room_name = #{room} AND host_key = #{key} AND status = #{status} "
            + "AND NOT EXISTS (SELECT 1 FROM meeting_host_authority WHERE room_name = #{room} AND state <> 'ACTIVE')")
    int updateSubjectAuthorized(@Param("room") String room, @Param("key") String key,
                                @Param("status") String status, @Param("subject") String subject);

    @Update("UPDATE meeting SET status = #{next} WHERE room_name = #{room} AND host_key = #{key} AND status = #{expected} "
            + "AND NOT EXISTS (SELECT 1 FROM meeting_host_authority WHERE room_name = #{room} AND state <> 'ACTIVE')")
    int updateStatusAuthorized(@Param("room") String room, @Param("key") String key,
                               @Param("expected") String expected, @Param("next") String next);

    @Update("UPDATE meeting SET status = 'RUNNING' WHERE room_name = #{room} AND status = 'SCHEDULED'")
    int startScheduled(@Param("room") String room);

    /**
     * 根据会议号查询会议。
     *
     * @param roomName 会议号
     * @return 会议记录
     */
    default Optional<Meeting> findByRoomName(String roomName) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(Meeting.class).eq(Meeting::getRoomName, roomName)));
    }

    /**
     * 查询桌面首页可见会议，只返回指定创建人的、尚未开始的预定会议。
     *
     * @param creatorId 创建人用户 ID
     * @return 可见会议列表
     */
    default List<Meeting> listVisible(String creatorId) {
        return selectList(
            Wrappers.lambdaQuery(Meeting.class)
                .eq(Meeting::getCreatorId, creatorId)
                .eq(Meeting::getStatus, Meeting.STATUS_SCHEDULED)
                .orderByAsc(Meeting::getScheduledStartTime)
                .last("LIMIT 100")
        );
    }

    /**
     * 查询指定创建人的历史会议记录，包含快速会议、预定会议、已取消会议和已过期会议。
     *
     * @param creatorId 创建人用户 ID
     * @return 历史会议列表
     */
    default List<Meeting> listHistory(String creatorId) {
        return selectList(
            Wrappers.lambdaQuery(Meeting.class)
                .eq(Meeting::getCreatorId, creatorId)
                .orderByDesc(Meeting::getCreatedAt)
                .last("LIMIT 200")
        );
    }
}
