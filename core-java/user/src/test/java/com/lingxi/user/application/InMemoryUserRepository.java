package com.lingxi.user.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserStatus;
import com.lingxi.user.domain.repo.UserRepository;

/**
 * 内存账号仓储替身（测试用）：语义与 MySQL 实现一致（租户过滤、用户名租户内唯一）。
 */
final class InMemoryUserRepository implements UserRepository {

    private final Map<String, LingxiUser> users = new ConcurrentHashMap<>();

    @Override
    public Optional<LingxiUser> findByUsername(String tenantId, String username) {
        return users.values().stream()
                .filter(u -> u.getTenantId().equals(tenantId) && u.getUsername().equals(username))
                .findFirst();
    }

    @Override
    public Optional<LingxiUser> findById(String userId, String tenantId) {
        LingxiUser user = users.get(userId);
        // 租户不匹配视为不存在（不泄露其他租户账号）
        return user != null && user.getTenantId().equals(tenantId) ? Optional.of(user) : Optional.empty();
    }

    @Override
    public boolean existsUsername(String tenantId, String username) {
        return findByUsername(tenantId, username).isPresent();
    }

    @Override
    public LingxiUser save(LingxiUser user) {
        users.put(user.getUserId(), user);
        return user;
    }

    @Override
    public int updatePasswordHash(String tenantId, String userId, String passwordHash, long atMs) {
        return findById(userId, tenantId).map(user -> {
            user.changePassword(passwordHash, atMs);
            return 1;
        }).orElse(0);
    }

    @Override
    public int updateStatus(String tenantId, String userId, UserStatus status, long atMs) {
        return findById(userId, tenantId).map(user -> {
            user.changeStatus(status, atMs);
            return 1;
        }).orElse(0);
    }

    @Override
    public int updateLastLogin(String tenantId, String userId, long atMs) {
        return findById(userId, tenantId).map(user -> {
            user.markLogin(atMs);
            return 1;
        }).orElse(0);
    }

    @Override
    public long count(String tenantId, String keyword) {
        return list(tenantId, keyword, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public List<LingxiUser> list(String tenantId, String keyword, int offset, int limit) {
        List<LingxiUser> matched = new ArrayList<>(users.values().stream()
                .filter(u -> u.getTenantId().equals(tenantId))
                .filter(u -> keyword == null || keyword.isBlank()
                        || u.getUsername().contains(keyword) || u.getDisplayName().contains(keyword))
                .sorted(Comparator.comparingLong(LingxiUser::getCreatedAtMs).reversed())
                .toList());
        if (offset >= matched.size()) {
            return List.of();
        }
        return matched.subList(offset, Math.min(offset + limit, matched.size()));
    }
}
