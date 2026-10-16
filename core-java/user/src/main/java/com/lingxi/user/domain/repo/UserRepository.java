package com.lingxi.user.domain.repo;

import java.util.List;
import java.util.Optional;

import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserStatus;

/**
 * 账号存储抽象（M5-5.6）：默认 MySQL 实现，内存实现供单元测试。
 *
 * <p>所有查询一律带租户条件（用户名租户内唯一），避免跨租户账号串读。</p>
 */
public interface UserRepository {

    /** 租户内按登录名查询（登录与查重用）。 */
    Optional<LingxiUser> findByUsername(String tenantId, String username);

    /** 租户内按用户 ID 查询。 */
    Optional<LingxiUser> findById(String userId, String tenantId);

    /** 租户内登录名是否已存在（创建前置校验）。 */
    boolean existsUsername(String tenantId, String username);

    /** 新增账号。 */
    LingxiUser save(LingxiUser user);

    /** 更新口令哈希。 */
    int updatePasswordHash(String tenantId, String userId, String passwordHash, long atMs);

    /** 更新状态。 */
    int updateStatus(String tenantId, String userId, UserStatus status, long atMs);

    /** 更新最近登录时间。 */
    int updateLastLogin(String tenantId, String userId, long atMs);

    /** 租户内用户数量是否达到上限（防账号数无序膨胀）。 */
    long count(String tenantId, String keyword);

    /** 租户内分页查询（keyword 匹配登录名或展示名）。 */
    List<LingxiUser> list(String tenantId, String keyword, int offset, int limit);
}
