package com.quant.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.core.entity.UserDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserMapper extends BaseMapper<UserDO> {
    @Update("UPDATE users SET password_hash = #{newHash} WHERE id = #{userId} AND BINARY password_hash = BINARY #{expectedHash}")
    int changePassword(@Param("userId") long userId, @Param("expectedHash") String expectedHash,
                       @Param("newHash") String newHash);
}
