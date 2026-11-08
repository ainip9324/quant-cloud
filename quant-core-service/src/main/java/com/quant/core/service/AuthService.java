package com.quant.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.core.dto.AuthRequest;
import com.quant.core.dto.ChangePasswordRequest;
import com.quant.core.entity.UserDO;
import com.quant.core.mapper.UserMapper;
import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.UserRole;
import com.quant.core.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 账户注册、用户名/邮箱登录、本人密码修改与访问令牌签发。
 *
 * <p>密码只以 BCrypt 哈希存储；JWT v2 使用 HMAC-SHA256 签名。
 * Gateway 和目标服务都会校验原始 Q-Token，并以 Token 中的可信主体覆盖外部伪造头。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final AuthProperties authProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Map<String, Object> register(AuthRequest request) {
        String username = clean(request.getUsername()).toLowerCase(Locale.ROOT);
        String email = clean(request.getEmail()).toLowerCase(Locale.ROOT);

        validateRegistration(username, email, request.getPassword());

        long exists = userMapper.selectCount(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username)
                .or()
                .eq(UserDO::getEmail, email));
        if (exists > 0) {
            throw new IllegalArgumentException("用户名或邮箱已被注册");
        }

        UserDO user = new UserDO();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.VIEWER);
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);

        return authResult(user);
    }

    public Map<String, Object> login(AuthRequest request) {
        String identifier = clean(request.getIdentifier());
        if (identifier.isEmpty()) identifier = clean(request.getEmail());
        if (identifier.isEmpty()) identifier = clean(request.getUsername());
        identifier = identifier.toLowerCase(Locale.ROOT);
        if (identifier.isEmpty() || request.getPassword() == null || request.getPassword().isEmpty()) {
            throw new IllegalArgumentException("用户名、邮箱或密码不正确");
        }
        LambdaQueryWrapper<UserDO> query = new LambdaQueryWrapper<>();
        if (identifier.contains("@")) query.eq(UserDO::getEmail, identifier);
        else query.eq(UserDO::getUsername, identifier);
        UserDO user = userMapper.selectOne(query);

        if (user == null || !passwordMatches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名、邮箱或密码不正确");
        }
        return authResult(user);
    }

    public void changePassword(long userId, ChangePasswordRequest request) {
        String newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.length() < 8
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("新密码至少8位，且UTF-8编码不超过72字节");
        }
        String currentPassword = request.getCurrentPassword();
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        UserDO user = userMapper.selectById(userId);
        if (user == null || !passwordMatches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        if (currentPassword.equals(newPassword)) throw new IllegalArgumentException("新密码不能与当前密码相同");
        String newHash = passwordEncoder.encode(newPassword);
        if (userMapper.changePassword(userId, user.getPasswordHash(), newHash) != 1) {
            throw new IllegalArgumentException("密码已发生变化，请重新输入当前密码");
        }
    }

    public Map<String, Object> me(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return publicUser(user);
    }

    private boolean passwordMatches(String password, String hash) {
        try { return passwordEncoder.matches(password, hash); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private Map<String, Object> authResult(UserDO user) {
        return Map.of(
                "token", createToken(user),
                "user", publicUser(user)
        );
    }

    private Map<String, Object> publicUser(UserDO user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail(),
                "role", user.getRole().name()
        );
    }

    /**
     * JWT 的结构是 base64Url(header).base64Url(payload).HMAC-SHA256(signature)。
     * payload 只放用户 ID、角色、版本与过期时间，用户资料仍以数据库为准。
     */
    private String createToken(UserDO user) {
        return authProperties.issuer().issue(
                new AuthenticatedPrincipal(user.getId(), user.getRole()), authProperties.tokenExpireSeconds());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateRegistration(String username, String email, String password) {
        if (!username.matches("[a-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("用户名需为 3-32 位小写字母、数字或下划线");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("请输入正确的邮箱地址");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
    }
}
