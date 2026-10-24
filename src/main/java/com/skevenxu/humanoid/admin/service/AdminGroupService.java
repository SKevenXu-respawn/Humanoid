package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skevenxu.humanoid.admin.dto.AdminDtos.GroupRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.PageResponse;
import com.skevenxu.humanoid.entity.BotGroup;
import com.skevenxu.humanoid.mapper.BotGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;

/** 群白名单管理。 */
@Service
public class AdminGroupService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotGroupMapper groupMapper;

    public AdminGroupService(BotGroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    public PageResponse<BotGroup> page(Integer page, Integer size, String keyword, Boolean enabled) {
        LambdaQueryWrapper<BotGroup> query = new LambdaQueryWrapper<>();
        if (enabled != null) {
            query.eq(BotGroup::getEnabled, enabled);
        }
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            Long groupId = null;
            try {
                groupId = Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // keyword is not a group id
            }
            Long finalGroupId = groupId;
            query.and(w -> {
                w.like(BotGroup::getName, text)
                        .or().like(BotGroup::getRemark, text);
                if (finalGroupId != null) {
                    w.or().eq(BotGroup::getGroupId, finalGroupId);
                }
            });
        }
        query.orderByDesc(BotGroup::getId);
        Page<BotGroup> result = groupMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    public BotGroup create(GroupRequest request) {
        if (request == null || request.groupId() == null) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
        if (groupMapper.selectCount(new LambdaQueryWrapper<BotGroup>()
                .eq(BotGroup::getGroupId, request.groupId())) > 0) {
            throw new IllegalStateException("该群已在白名单中");
        }
        BotGroup group = new BotGroup();
        group.setGroupId(request.groupId());
        group.setName(trim(request.name(), 128));
        group.setRemark(trim(request.remark(), 128));
        group.setEnabled(request.enabled() == null || request.enabled());
        LocalDateTime now = LocalDateTime.now(ZONE);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groupMapper.insert(group);
        return group;
    }

    public BotGroup update(Long id, GroupRequest request) {
        BotGroup group = get(id);
        if (request != null && request.groupId() != null && !request.groupId().equals(group.getGroupId())) {
            throw new IllegalStateException("不能修改群号，请删除后重新添加");
        }
        if (request != null) {
            if (request.name() != null) {
                group.setName(trim(request.name(), 128));
            }
            if (request.remark() != null) {
                group.setRemark(trim(request.remark(), 128));
            }
            if (request.enabled() != null) {
                group.setEnabled(request.enabled());
            }
        }
        group.setUpdatedAt(LocalDateTime.now(ZONE));
        groupMapper.updateById(group);
        return group;
    }

    public BotGroup setEnabled(Long id, boolean enabled) {
        BotGroup group = get(id);
        group.setEnabled(enabled);
        group.setUpdatedAt(LocalDateTime.now(ZONE));
        groupMapper.updateById(group);
        return group;
    }

    public void delete(Long id) {
        get(id);
        groupMapper.deleteById(id);
    }

    private BotGroup get(Long id) {
        BotGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new NoSuchElementException("群不存在: " + id);
        }
        return group;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
