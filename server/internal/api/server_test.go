package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
	"github.com/gorilla/websocket"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type tokenTransport struct {
	token string
}

func (t tokenTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	clone := req.Clone(req.Context())
	clone.Header.Set("Authorization", "Bearer "+t.token)
	return http.DefaultTransport.RoundTrip(clone)
}

func TestMCPToDeviceRoundTrip(t *testing.T) {
	hub := bridge.NewHub()
	httpServer := httptest.NewServer(NewHandler(hub, Config{DeviceToken: "device-secret", MCPToken: "mcp-secret"}))
	defer httpServer.Close()
	deviceURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/device?id=primary"
	header := http.Header{"Authorization": []string{"Bearer device-secret"}}
	conn, _, err := websocket.DefaultDialer.Dial(deviceURL, header)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	methods := make(chan string, 2)
	go func() {
		for {
			var command bridge.Command
			if err := conn.ReadJSON(&command); err != nil {
				return
			}
			methods <- command.Method
			result := map[string]any{"done": true}
			if command.Method == "get_screen" {
				result = map[string]any{"revision": 1, "width": 1080, "height": 2400,
					"tree":        map[string]any{"path": "0", "text": "Hello"},
					"imageBase64": base64.StdEncoding.EncodeToString([]byte("fake-jpeg"))}
			}
			_ = conn.WriteJSON(bridge.Response{ID: command.ID, OK: true, Result: mustJSON(result)})
		}
	}()
	deadline := time.Now().Add(time.Second)
	for len(hub.Status()) == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if len(hub.Status()) != 1 {
		t.Fatal("device did not attach")
	}
	client := mcp.NewClient(&mcp.Implementation{Name: "test", Version: "0.1.0"}, nil)
	transport := &mcp.StreamableClientTransport{
		Endpoint: httpServer.URL + "/mcp", DisableStandaloneSSE: true,
		HTTPClient: &http.Client{Transport: tokenTransport{token: "mcp-secret"}},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	session, err := client.Connect(ctx, transport, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	screen, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "get_screen", Arguments: map[string]any{}})
	if err != nil || screen.IsError || len(screen.Content) != 2 {
		t.Fatalf("screen: result=%+v err=%v", screen, err)
	}
	if got := screen.Content[1].(*mcp.ImageContent).Data; string(got) != "fake-jpeg" {
		t.Fatalf("image data = %q", got)
	}
	tap, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "tap", Arguments: map[string]any{"x": 12, "y": 34}})
	if err != nil || tap.IsError {
		t.Fatalf("tap: result=%+v err=%v", tap, err)
	}
	if <-methods != "get_screen" || <-methods != "tap" {
		t.Fatal("unexpected command sequence")
	}
}

func TestTokensRequired(t *testing.T) {
	srv := httptest.NewServer(NewHandler(bridge.NewHub(), Config{DeviceToken: "d", MCPToken: "m"}))
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/mcp")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d", resp.StatusCode)
	}
}

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
