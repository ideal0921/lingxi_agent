// Package handler 网关 HTTP 处理器。
package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// Health 健康检查接口，供 LB / K8s 探活使用。
func Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"service": "gateway-go",
	})
}
