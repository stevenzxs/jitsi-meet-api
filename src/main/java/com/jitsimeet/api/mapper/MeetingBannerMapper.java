package com.jitsimeet.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jitsimeet.api.domain.MeetingBanner;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会议电子屏 Mapper，使用 MyBatis Plus 管理 SQLite 中的 meeting_banner 表。
 */
@Mapper
public interface MeetingBannerMapper extends BaseMapper<MeetingBanner> {
}
