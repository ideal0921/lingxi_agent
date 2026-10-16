// Package auth 网关 JWT 校验（M5-5.5）：仅支持 HS256，密钥来自环境变量，禁止硬编码。
package auth

import (
	"errors"
	"fmt"

	"github.com/golang-jwt/jwt/v5"
)

// Claims 网关关心的身份声明：租户、用户与角色。
type Claims struct {
	TenantID string
	UserID   string
	Role     string
}

// 声明名兼容：优先平台约定的短名，回落到常见长名。
var (
	claimTenantID = []string{"tid", "tenant_id", "tenantId"}
	claimUserID   = []string{"sub", "uid", "user_id", "userId"}
	claimRole     = []string{"role", "roles"}
)

// ErrEmptySecret 未配置签名密钥：拒绝一切令牌，避免「空密钥即通过」的降级漏洞。
var ErrEmptySecret = errors.New("未配置 JWT 签名密钥")

// ParseToken 校验 JWT 签名与时效，并提取租户身份。
//
// 安全约束：显式限定签名算法为 HS256（拒绝 alg=none 与算法混淆攻击）；
// 过期/nbf 由库自动校验。
func ParseToken(token, secret string) (Claims, error) {
	if secret == "" {
		return Claims{}, ErrEmptySecret
	}
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("非预期的签名算法: %v", t.Header["alg"])
		}
		return []byte(secret), nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
	if err != nil {
		return Claims{}, err
	}
	claims, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return Claims{}, errors.New("令牌声明格式非法")
	}
	return Claims{
		TenantID: firstString(claims, claimTenantID),
		UserID:   firstString(claims, claimUserID),
		Role:     firstString(claims, claimRole),
	}, nil
}

// firstString 依次尝试多个候选声明名，返回首个非空字符串值。
func firstString(claims jwt.MapClaims, names []string) string {
	for _, name := range names {
		if value, ok := claims[name].(string); ok && value != "" {
			return value
		}
	}
	return ""
}
