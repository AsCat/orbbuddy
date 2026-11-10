package api

import (
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"strings"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
	"github.com/gorilla/websocket"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

var validDeviceID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

//go:embed index.html
var indexHTML string

//go:embed viewer.js
var viewerJS string

//go:embed openapi.json
var openAPIJSON string

type Config struct {
	DeviceToken string
	MCPToken    string
}

func NewHandler(hub *bridge.Hub, cfg Config) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
		_, _ = w.Write([]byte(indexHTML))
	})
	mux.HandleFunc("GET /viewer.js", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		_, _ = w.Write([]byte(viewerJS))
	})
	mux.HandleFunc("GET /openapi.json", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=300")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		_, _ = w.Write([]byte(openAPIJSON))
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	upgrader := websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096}
	mux.HandleFunc("GET /device", func(w http.ResponseWriter, r *http.Request) {
		if !bearerMatches(r, cfg.DeviceToken) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		id := r.URL.Query().Get("id")
		if !validDeviceID.MatchString(id) {
			http.Error(w, "invalid device id", http.StatusBadRequest)
			return
		}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("device upgrade: %v", err)
			return
		}
		hub.Attach(id, conn)
	})
	mcpServer := NewMCPServer(hub)
	mcpHandler := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server {
		return mcpServer
	}, &mcp.StreamableHTTPOptions{Stateless: true, JSONResponse: true})
	mux.Handle("/mcp", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !bearerMatches(r, cfg.MCPToken) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		mcpHandler.ServeHTTP(w, r)
	}))
	newHTTPAPI(hub).register(mux)
	return mux
}

func bearerMatches(r *http.Request, want string) bool {
	got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	return want != "" && len(got) == len(want) && subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

type screenResult struct {
	Revision    int64           `json:"revision"`
	Width       int             `json:"width"`
	Height      int             `json:"height"`
	Tree        json.RawMessage `json:"tree"`
	ImageBase64 string          `json:"imageBase64"`
	ImageError  string          `json:"imageError,omitempty"`
	CapturedAt  string          `json:"capturedAt,omitempty"`
}

func screenContent(raw json.RawMessage) ([]mcp.Content, error) {
	var screen screenResult
	if err := json.Unmarshal(raw, &screen); err != nil {
		return nil, err
	}
	meta, _ := json.Marshal(map[string]any{
		"revision": screen.Revision, "width": screen.Width, "height": screen.Height,
		"tree": json.RawMessage(screen.Tree), "imageError": screen.ImageError,
	})
	content := []mcp.Content{&mcp.TextContent{Text: string(meta)}}
	if screen.ImageBase64 != "" {
		image, err := base64.StdEncoding.DecodeString(screen.ImageBase64)
		if err != nil {
			return nil, err
		}
		content = append(content, &mcp.ImageContent{Data: image, MIMEType: "image/jpeg"})
	}
	return content, nil
}
