# 构建阶段
FROM golang:1.22-alpine AS builder
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/gateway ./cmd/gateway

# 运行阶段（无 shell 的极简镜像）
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/gateway /gateway
EXPOSE 8080
ENTRYPOINT ["/gateway"]
