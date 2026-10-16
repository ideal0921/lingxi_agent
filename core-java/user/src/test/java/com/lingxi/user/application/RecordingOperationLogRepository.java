package com.lingxi.user.application;

import java.util.ArrayList;
import java.util.List;

import com.lingxi.common.security.JwtClaims;
import com.lingxi.common.security.JwtIssuer;
import com.lingxi.common.security.JwtVerifier;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.repo.OperationLogRepository;

/**
 * 记录型操作日志仓储替身（测试用）：可断言审计是否落库，且可注入故障。
 */
final class RecordingOperationLogRepository implements OperationLogRepository {

    private final List<OperationLog> logs = new ArrayList<>();
    /** 置为 true 时 append 抛异常，用于验证「审计失败不阻断主链路」。 */
    boolean failOnAppend;

    @Override
    public void append(OperationLog log) {
        if (failOnAppend) {
            throw new IllegalStateException("模拟日志落库失败");
        }
        logs.add(log);
    }

    @Override
    public List<OperationLog> list(com.lingxi.log.application.OperationLogQuery query, int offset, int limit) {
        return logs.stream()
                .filter(log -> log.tenantId().equals(query.tenantId()))
                .filter(log -> query.module() == null || query.module().equals(log.module()))
                .filter(log -> query.action() == null || query.action().equals(log.action()))
                .filter(log -> query.result() == null || query.result().equals(log.result()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(com.lingxi.log.application.OperationLogQuery query) {
        return list(query, 0, Integer.MAX_VALUE).size();
    }

    List<OperationLog> logs() {
        return logs;
    }

    /** 便捷过滤器：按模块与动作取出日志。 */
    List<OperationLog> logsOf(String module, String action) {
        return logs.stream()
                .filter(log -> module.equals(log.module()) && action.equals(log.action()))
                .toList();
    }

    /** 与生产同源的签发/校验器（同一密钥，验证 token 可被验签）。 */
    static final class TestTokens {

        private static final String SECRET = "unit-test-secret-0123456789-abcdefghij";

        static JwtIssuer issuer() {
            return new JwtIssuer(SECRET, 3600);
        }

        static JwtVerifier verifier() {
            return new JwtVerifier(SECRET);
        }

        static JwtClaims verify(String token) {
            return verifier().verify(token);
        }

        private TestTokens() {
        }
    }

    /** 构造日志服务（可注入故障）。 */
    static OperationLogService service(RecordingOperationLogRepository repository) {
        return new OperationLogService(repository);
    }
}
