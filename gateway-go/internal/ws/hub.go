package ws

import (
	"sync"
)

// Hub 管理所有会话连接：按 sessionID 索引一组连接。
//
// 同一会话允许多个连接并存（用户端 + 坐席工作台观察端），
// 广播时下发给该会话的全部连接；坐席回复等单向下行也复用该机制。
type Hub struct {
	mu      sync.RWMutex
	clients map[string]map[*Client]struct{}
}

// NewHub 创建连接管理器。
func NewHub() *Hub {
	return &Hub{clients: make(map[string]map[*Client]struct{})}
}

// Register 注册连接（同一会话可注册多个）。
func (h *Hub) Register(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.clients[c.sessionID]
	if set == nil {
		set = make(map[*Client]struct{})
		h.clients[c.sessionID] = set
	}
	set[c] = struct{}{}
}

// Unregister 注销指定连接；该会话最后一个连接移除后清理索引。
func (h *Hub) Unregister(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.clients[c.sessionID]
	if set == nil {
		return
	}
	delete(set, c)
	if len(set) == 0 {
		delete(h.clients, c.sessionID)
	}
}

// Broadcast 向指定会话的全部连接推送消息（坐席回复等下行）。
func (h *Hub) Broadcast(sessionID string, env *Envelope) {
	h.BroadcastExcept(sessionID, nil, env)
}

// BroadcastExcept 向指定会话推送消息，排除 exclude 连接（用户自身消息无需回显）。
//
// 先快照目标连接再释放读锁，避免持锁期间执行网络写操作。
func (h *Hub) BroadcastExcept(sessionID string, exclude *Client, env *Envelope) {
	h.mu.RLock()
	set := h.clients[sessionID]
	targets := make([]*Client, 0, len(set))
	for c := range set {
		if c != exclude {
			targets = append(targets, c)
		}
	}
	h.mu.RUnlock()

	for _, c := range targets {
		c.deliver(env)
	}
}

// BroadcastToRole 仅向指定会话中角色匹配的连接推送（如建议结果仅推坐席端，防泄漏给用户端）。
//
// 与 BroadcastExcept 同样先快照再释放读锁，避免持锁期间执行网络写操作。
func (h *Hub) BroadcastToRole(sessionID, role string, env *Envelope) {
	h.mu.RLock()
	set := h.clients[sessionID]
	targets := make([]*Client, 0, len(set))
	for c := range set {
		if c.Role() == role {
			targets = append(targets, c)
		}
	}
	h.mu.RUnlock()

	for _, c := range targets {
		c.deliver(env)
	}
}

// Count 返回指定会话当前的连接数（用于观测与测试）。
func (h *Hub) Count(sessionID string) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[sessionID])
}
