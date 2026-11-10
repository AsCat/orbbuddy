package bridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sort"
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
	ID           string    `json:"id"`
	Online       bool      `json:"online"`
	ConnectedAt  time.Time `json:"connectedAt"`
	LastSeenAt   time.Time `json:"lastSeenAt"`
	Paused       bool      `json:"paused"`
	Manufacturer string    `json:"manufacturer,omitempty"`
	Model        string    `json:"model,omitempty"`
}

type Event struct {
	Name string `json:"event"`
}

type pendingResult struct {
	response Response
	err      error
}

type device struct {
	id           string
	connectedAt  time.Time
	lastSeenAt   time.Time
	paused       bool
	manufacturer string
	model        string
	conn         *websocket.Conn
	writeMu      sync.Mutex
	opMu         sync.Mutex
	mu           sync.Mutex
	pending      map[string]chan pendingResult
	closed       bool
}

type Hub struct {
	mu      sync.RWMutex
	devices map[string]*device
	seen    map[string]DeviceStatus
	onEvent func(string, Event)
}

func NewHub() *Hub {
	return &Hub{devices: make(map[string]*device), seen: make(map[string]DeviceStatus)}
}

func (h *Hub) SetEventHandler(handler func(string, Event)) {
	h.mu.Lock()
	h.onEvent = handler
	h.mu.Unlock()
}

// Attach replaces an older connection for the same device. It blocks until the
// socket closes, so the HTTP upgrade handler owns the connection lifecycle.
func (h *Hub) Attach(id string, conn *websocket.Conn) {
	now := time.Now().UTC()
	d := &device{id: id, connectedAt: now, lastSeenAt: now, conn: conn, pending: make(map[string]chan pendingResult)}
	h.mu.Lock()
	old := h.devices[id]
	h.devices[id] = d
	h.seen[id] = DeviceStatus{ID: id, Online: true, ConnectedAt: now, LastSeenAt: now}
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
			status := h.seen[id]
			status.Online = false
			h.seen[id] = status
		}
		h.mu.Unlock()
		log.Printf("device %s disconnected", id)
	}()
	conn.SetReadLimit(5 << 20)
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPingHandler(func(message string) error {
		if err := conn.SetReadDeadline(time.Now().Add(70 * time.Second)); err != nil {
			return err
		}
		return conn.WriteControl(websocket.PongMessage, []byte(message), time.Now().Add(10*time.Second))
	})
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(70 * time.Second)) })
	stopHeartbeat := make(chan struct{})
	defer close(stopHeartbeat)
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				d.writeMu.Lock()
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				err := conn.WriteJSON(Command{ID: "__heartbeat__", Method: "heartbeat", Params: map[string]any{}})
				d.writeMu.Unlock()
				if err != nil {
					_ = conn.Close()
					return
				}
			case <-stopHeartbeat:
				return
			}
		}
	}()
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			log.Printf("device %s socket closed: %v", id, err)
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
		var event Event
		if json.Unmarshal(data, &event) == nil && event.Name != "" {
			h.mu.RLock()
			current := h.devices[id] == d
			handler := h.onEvent
			h.mu.RUnlock()
			if current && handler != nil {
				handler(id, event)
			}
			continue
		}
		var response Response
		if json.Unmarshal(data, &response) != nil || response.ID == "" {
			continue
		}
		d.mu.Lock()
		d.lastSeenAt = time.Now().UTC()
		if response.ID == "__heartbeat__" && response.OK {
			var heartbeat struct {
				Paused       bool   `json:"paused"`
				Manufacturer string `json:"manufacturer"`
				Model        string `json:"model"`
			}
			if json.Unmarshal(response.Result, &heartbeat) == nil {
				d.paused = heartbeat.Paused
				d.manufacturer = heartbeat.Manufacturer
				d.model = heartbeat.Model
			}
		}
		ch := d.pending[response.ID]
		delete(d.pending, response.ID)
		lastSeenAt, paused, manufacturer, model := d.lastSeenAt, d.paused, d.manufacturer, d.model
		d.mu.Unlock()
		h.mu.Lock()
		if h.devices[id] == d {
			h.seen[id] = DeviceStatus{ID: id, Online: true, ConnectedAt: d.connectedAt, LastSeenAt: lastSeenAt,
				Paused: paused, Manufacturer: manufacturer, Model: model}
		}
		h.mu.Unlock()
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
		d.mu.Lock()
		lastSeenAt := d.lastSeenAt
		paused := d.paused
		manufacturer := d.manufacturer
		model := d.model
		d.mu.Unlock()
		out = append(out, DeviceStatus{ID: d.id, Online: true, ConnectedAt: d.connectedAt, LastSeenAt: lastSeenAt,
			Paused: paused, Manufacturer: manufacturer, Model: model})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// LookupStatus retains only connectivity metadata after a device disconnects.
func (h *Hub) LookupStatus(id string) (DeviceStatus, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	status, ok := h.seen[id]
	return status, ok
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
	_ = d.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
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
		// A non-responsive socket must not remain listed as an online device.
		d.close(ctx.Err())
		return nil, ctx.Err()
	}
}
