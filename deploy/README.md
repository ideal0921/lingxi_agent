# deploy — 部署交付

本目录承载多云与私有化交付（规划文档 3.5 节）。

**M1 状态说明**：骨架阶段以本地 `docker-compose.dev.yml` + Dockerfile 验证运行为主，
Helm Charts 与离线安装包在 M3/M5 按里程碑逐步落地：

| 交付物 | 里程碑 |
|---|---|
| 基础 Helm Chart（四服务 + infra） | M1 收尾（跟随 CI 镜像） |
| 私有化离线安装包（镜像 tar + installer.sh） | M5 |
| 多云双活编排（ArgoCD / 跨云同步） | M5 |

## M1 本地验证

```bash
# 中间件
docker compose -f ../docker-compose.dev.yml up -d

# 服务（各开终端）
cd ../gateway-go && go run ./cmd/gateway        # :8080
cd ../core-java  && mvn -q -pl start spring-boot:run  # :8081
cd ../ai-python  && make dev                    # :8082
cd ../web-vue3   && npm run dev                 # :3000
```
