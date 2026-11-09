package bridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var ErrOffline = errors.New("device is offline")

type Command struct {
	ID     string `json:"id"`
	Method string `json:"method"`
	Params any    `json:"params"`
}

type Response struct {
	ID     string          `json:"id"`
	OK     bool            `json:"ok"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  string          `json:"error,omitempty"`
}

type DeviceStatus struct {
	ID          string    `json:"id"`
	ConnectedAt time.Time `json:"connectedAt"`
}

type pendingResult struct {
	response Response
	err      error
}

type device struct {
	id          string
	connectedAt time.Time
	conn        *websocket.Conn
	writeMu     sync.Mutex
	opMu        sync.Mutex
	mu          sync.Mutex
	pending     map[string]chan pendingResult
	closed      bool
}

type Hub struct {
	mu      sync.RWMutex
	devices map[string]*device
}

func NewHub() *Hub { return &Hub{devices: make(map[string]*device)} }

// Attach replaces an older connection for the same device. It blocks until the
// socket closes, so the HTTP upgrade handler owns the connection lifecycle.
func (h *Hub) Attach(id string, conn *websocket.Conn) {
	d := &device{id: id, connectedAt: time.Now().UTC(), conn: conn, pending: make(map[string]chan pendingResult)}
	h.mu.Lock()
	old := h.devices[id]
	h.devices[id] = d
	h.mu.Unlock()
	log.Printf("device %s connected", id)
	if old != nil {
		old.close(errors.New("device reconnected"))
	}
	defer func() {
		d.close(ErrOffline)
		h.mu.Lock()
		if h.devices[id] == d {
			delete(h.devices, id)
		}
		h.mu.Unlock()
		log.Printf("device %s disconnected", id)
	}()
	conn.SetReadLimit(5 << 20)
	_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPingHandler(func(message string) error {
		if err := conn.SetReadDeadline(time.Now().Add(90 * time.Second)); err != nil {
			return err
		}
		return conn.WriteControl(websocket.PongMessage, []byte(message), time.Now().Add(10*time.Second))
	})
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(90 * time.Second)) })
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			log.Printf("device %s socket closed: %v", id, err)
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		var response Response
		if json.Unmarshal(data, &response) != nil || response.ID == "" {
			continue
		}
		d.mu.Lock()
		ch := d.pending[response.ID]
		delete(d.pending, response.ID)
		d.mu.Unlock()
		if ch != nil {
			ch <- pendingResult{response: response}
		}
	}
}

func (d *device) close(reason error) {
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return
	}
	d.closed = true
	pending := d.pending
	d.pending = make(map[string]chan pendingResult)
	d.mu.Unlock()
	_ = d.conn.Close()
	for _, ch := range pending {
		ch <- pendingResult{err: reason}
	}
}

func (h *Hub) Status() []DeviceStatus {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]DeviceStatus, 0, len(h.devices))
	for _, d := range h.devices {
		out = append(out, DeviceStatus{ID: d.id, ConnectedAt: d.connectedAt})
	}
	return out
}

func (h *Hub) Call(ctx context.Context, deviceID, method string, params any) (json.RawMessage, error) {
	h.mu.RLock()
	d := h.devices[deviceID]
	h.mu.RUnlock()
	if d == nil {
		return nil, ErrOffline
	}
	// Serializing calls prevents a tap from racing with its preceding screenshot.
	d.opMu.Lock()
	defer d.opMu.Unlock()
	var idBytes [16]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		return nil, err
	}
	id := hex.EncodeToString(idBytes[:])
	ch := make(chan pendingResult, 1)
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return nil, ErrOffline
	}
	d.pending[id] = ch
	d.mu.Unlock()
	defer func() {
		d.mu.Lock()
		delete(d.pending, id)
		d.mu.Unlock()
	}()
	d.writeMu.Lock()
	err := d.conn.WriteJSON(Command{ID: id, Method: method, Params: params})
	d.writeMu.Unlock()
	if err != nil {
		return nil, fmt.Errorf("send command: %w", err)
	}
	select {
	case result := <-ch:
		if result.err != nil {
			return nil, result.err
		}
		if !result.response.OK {
			return nil, errors.New(result.response.Error)
		}
		return result.response.Result, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
