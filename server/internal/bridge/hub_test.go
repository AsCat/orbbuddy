package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestReconnectReplacesOldSocketAndAcceptsCommands(t *testing.T) {
	hub := NewHub()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		hub.Attach("primary", conn)
	}))
	defer srv.Close()
	url := "ws" + strings.TrimPrefix(srv.URL, "http")
	old, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer old.Close()
	connected := waitForStatus(t, hub, true)
	newConn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer newConn.Close()
	deadline := time.Now().Add(time.Second)
	for !hub.Status()[0].ConnectedAt.After(connected.ConnectedAt) && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !hub.Status()[0].ConnectedAt.After(connected.ConnectedAt) {
		t.Fatal("new socket did not replace the old one")
	}
	_ = old.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := old.ReadMessage(); err == nil {
		t.Fatal("old socket remained open")
	}
	responseSent := make(chan struct{})
	go func() {
		defer close(responseSent)
		var command Command
		if err := newConn.ReadJSON(&command); err != nil {
			return
		}
		_ = newConn.WriteJSON(Response{ID: command.ID, OK: true, Result: json.RawMessage(`{"alive":true}`)})
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	result, err := hub.Call(ctx, "primary", "get_screen", map[string]any{})
	if err != nil || string(result) != `{"alive":true}` {
		t.Fatalf("new socket call = %s, %v", result, err)
	}
	if len(hub.Status()) != 1 {
		t.Fatal("old socket teardown removed the new device")
	}
	<-responseSent
	if err := newConn.WriteJSON(Response{ID: "__heartbeat__", OK: true, Result: json.RawMessage(`{"alive":true,"paused":true}`)}); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(time.Second)
	for !hub.Status()[0].Paused && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !hub.Status()[0].Paused {
		t.Fatal("phone pause state did not reach device_status")
	}
}

func waitForStatus(t *testing.T, hub *Hub, want bool) DeviceStatus {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		statuses := hub.Status()
		if want && len(statuses) == 1 {
			return statuses[0]
		}
		if !want && len(statuses) == 0 {
			return DeviceStatus{}
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("device status did not become %v", want)
	return DeviceStatus{}
}

func TestTimedOutCallRemovesUnresponsiveDevice(t *testing.T) {
	hub := NewHub()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		hub.Attach("primary", conn)
	}))
	defer srv.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	deadline := time.Now().Add(time.Second)
	for len(hub.Status()) == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if len(hub.Status()) == 0 {
		t.Fatal("device did not connect")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err = hub.Call(ctx, "primary", "get_screen", map[string]any{})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Call error = %v", err)
	}
	deadline = time.Now().Add(time.Second)
	for len(hub.Status()) != 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if len(hub.Status()) != 0 {
		t.Fatal("unresponsive device still appears online")
	}
}
