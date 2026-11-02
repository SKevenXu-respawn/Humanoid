package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.admin.dto.AdminDtos.UserRequest;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;

/** 用户白名单管理。 */
@Service
public class AdminUserService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotUserMapper userMapper;

    public AdminUserService(BotUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public PageResponse<BotUser> page(Integer page, Integer size, String keyword, Boolean enabled) {
        LambdaQueryWrapper<BotUser> query = new LambdaQueryWrapper<>();
        if (enabled != null) {
            query.eq(BotUser::getEnabled, enabled);
        }
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            Long qq = null;
            try {
                qq = Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // keyword is not a QQ number
            }
            Long finalQq = qq;
            query.and(w -> {
                w.like(BotUser::getNickname, text)
                        .or().like(BotUser::getRemark, text);
                if (finalQq != null) {
                    w.or().eq(BotUser::getUserId, finalQq);
                }
            });
        }
        query.orderByDesc(BotUser::getId);
        Page<BotUser> result = userMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotUser create(UserRequest request) {
        if (request == null || request.userId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getUserId, request.userId())) > 0) {
            throw new IllegalStateException("该 QQ 号已在白名单中");
        }
        BotUser user = new BotUser();
        user.setUserId(request.userId());
        user.setNickname(trim(request.nickname(), 64));
        user.setRemark(trim(request.remark(), 128));
        user.setEnabled(request.enabled() == null || request.enabled());
        LocalDateTime now = LocalDateTime.now(ZONE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    public BotUser update(Long id, UserRequest request) {
        BotUser user = get(id);
        if (request != null && request.userId() != null && !request.userId().equals(user.getUserId())) {
            throw new IllegalStateException("不能修改 QQ 号，请删除后重新添加");
        }
        if (request != null) {
            if (request.nickname() != null) {
                user.setNickname(trim(request.nickname(), 64));
            }
            if (request.remark() != null) {
                user.setRemark(trim(request.remark(), 128));
            }
            if (request.enabled() != null) {
                user.setEnabled(request.enabled());
            }
        }
        user.setUpdatedAt(LocalDateTime.now(ZONE));
        userMapper.updateById(user);
        return user;
    }

    public BotUser setEnabled(Long id, boolean enabled) {
        BotUser user = get(id);
        user.setEnabled(enabled);
        user.setUpdatedAt(LocalDateTime.now(ZONE));
        userMapper.updateById(user);
        return user;
    }

    public void delete(Long id) {
        get(id);
        userMapper.deleteById(id);
    }

    private BotUser get(Long id) {
        BotUser user = userMapper.selectById(id);
        if (user == null) {
            throw new NoSuchElementException("用户不存在: " + id);
        }
        return user;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
