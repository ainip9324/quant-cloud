package com.quant.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.quant.common.auth.UserRole;
import java.time.LocalDateTime;

@Data
@TableName("users")
public class UserDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String email;
    private String passwordHash;
    private UserRole role;
    private LocalDateTime createTime;
}
