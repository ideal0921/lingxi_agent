package auth

import (
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const testSecret = "unit-test-secret"

// signToken 生成测试用 HS256 令牌（仅用于测试，生产密钥来自环境变量）。
func signToken(t *testing.T, secret string, claims jwt.MapClaims) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("签发测试令牌失败: %v", err)
	}
	return signed
}

func TestParseTokenExtractsIdentity(t *testing.T) {
	raw := signToken(t, testSecret, jwt.MapClaims{
		"tid": "tenant-a", "sub": "u-1", "role": "agent",
		"exp": time.Now().Add(time.Hour).Unix(),
	})
	claims, err := ParseToken(raw, testSecret)
	if err != nil {
		t.Fatalf("合法令牌应解析成功: %v", err)
	}
	if claims.TenantID != "tenant-a" || claims.UserID != "u-1" || claims.Role != "agent" {
		t.Fatalf("身份提取不符: %+v", claims)
	}
}

func TestParseTokenSupportsLongClaimNames(t *testing.T) {
	raw := signToken(t, testSecret, jwt.MapClaims{
		"tenant_id": "tenant-b", "user_id": "u-2",
		"exp": time.Now().Add(time.Hour).Unix(),
	})
	claims, err := ParseToken(raw, testSecret)
	if err != nil {
		t.Fatalf("长声明名令牌应解析成功: %v", err)
	}
	if claims.TenantID != "tenant-b" || claims.UserID != "u-2" {
		t.Fatalf("长声明名提取不符: %+v", claims)
	}
}

func TestParseTokenRejectsWrongSecret(t *testing.T) {
	raw := signToken(t, "other-secret", jwt.MapClaims{"tid": "tenant-a"})
	if _, err := ParseToken(raw, testSecret); err == nil {
		t.Fatal("错误密钥签发的令牌必须被拒绝")
	}
}

func TestParseTokenRejectsMissingSecret(t *testing.T) {
	raw := signToken(t, testSecret, jwt.MapClaims{"tid": "tenant-a"})
	if _, err := ParseToken(raw, ""); err == nil {
		t.Fatal("未配置密钥时必须拒绝（防「空密钥即通过」）")
	}
}

func TestParseTokenRejectsExpired(t *testing.T) {
	raw := signToken(t, testSecret, jwt.MapClaims{
		"tid": "tenant-a", "exp": time.Now().Add(-time.Minute).Unix(),
	})
	if _, err := ParseToken(raw, testSecret); err == nil {
		t.Fatal("过期令牌必须被拒绝")
	}
}

func TestParseTokenRejectsAlgorithmDowngrade(t *testing.T) {
	// alg=none 的未签名令牌：必须被算法白名单拒绝
	unsigned := jwt.NewWithClaims(jwt.SigningMethodNone, jwt.MapClaims{"tid": "tenant-a"})
	raw, err := unsigned.SignedString(jwt.UnsafeAllowNoneSignatureType)
	if err != nil {
		t.Fatalf("构造 alg=none 令牌失败: %v", err)
	}
	if _, err := ParseToken(raw, testSecret); err == nil {
		t.Fatal("alg=none 令牌必须被拒绝")
	}
}

func TestParseTokenGarbageInput(t *testing.T) {
	if _, err := ParseToken("not-a-jwt", testSecret); err == nil {
		t.Fatal("非 JWT 结构必须被拒绝")
	}
}
