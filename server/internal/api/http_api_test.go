package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
	"github.com/gorilla/websocket"
)

func testDevice(t *testing.T, scope string) (*httptest.Server, *websocket.Conn, *atomic.Int32) {
	t.Helper()
	hub := bridge.NewHub()
	srv := httptest.NewServer(NewHandler(hub, Config{DeviceToken: "device-secret", MCPToken: "mcp-secret"}))
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/device?id=primary"
	conn, _, err := websocket.DefaultDialer.Dial(url, http.Header{"Authorization": []string{"Bearer device-secret"}})
	if err != nil {
		t.Fatal(err)
	}
	var taps atomic.Int32
	go func() {
		used := false
		for {
			var command bridge.Command
			if conn.ReadJSON(&command) != nil {
				return
			}
			result := map[string]any{"done": true}
			response := bridge.Response{ID: command.ID, OK: true}
			switch command.Method {
			case "auth_pair":
				params := command.Params.(map[string]any)
				if params["code"] != "0123" || used {
					response.OK, response.Error = false, "invalid or expired pairing code"
				} else {
					used = true
					result = map[string]any{"scope": scope, "grantId": strings.Repeat("a", 64), "allowedPackage": "dev.example.debug"}
				}
			case "get_screen":
				result = map[string]any{"width": 1080, "height": 2400, "revision": 3,
					"capturedAt":  time.Now().UTC().Format(time.RFC3339Nano),
					"imageBase64": base64.StdEncoding.EncodeToString([]byte("jpeg-bytes"))}
			case "tap":
				taps.Add(1)
			case "auth_revoke":
				used = false
			}
			if response.OK {
				response.Result = mustJSON(result)
			}
			if conn.WriteJSON(response) != nil {
				return
			}
		}
	}()
	deadline := time.Now().Add(time.Second)
	for len(hub.Status()) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(hub.Status()) == 0 {
		t.Fatal("device did not attach")
	}
	return srv, conn, &taps
}

func apiRequest(t *testing.T, method, url, token, body string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, url, bytes.NewBufferString(body))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func pairTest(t *testing.T, url, scope string) string {
	t.Helper()
	resp := apiRequest(t, "POST", url+"/api/v1/auth/pair", "", `{"device_id":"primary","code":"0123"}`)
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		data, _ := io.ReadAll(resp.Body)
		t.Fatalf("pair status %d: %s", resp.StatusCode, data)
	}
	var result struct {
		AccessToken string `json:"access_token"`
		Scope       string `json:"scope"`
	}
	if json.NewDecoder(resp.Body).Decode(&result) != nil || result.Scope != scope || len(result.AccessToken) < 40 {
		t.Fatalf("bad pair result: %+v", result)
	}
	return result.AccessToken
}

func TestHTTPSViewerRoundTripAndRevocation(t *testing.T) {
	srv, conn, taps := testDevice(t, "viewer")
	defer srv.Close()
	defer conn.Close()
	root := srv.URL + "/api/v1/devices/primary"
	resp := apiRequest(t, "GET", root+"/status", "", "")
	if resp.StatusCode != 401 {
		t.Fatalf("unauthenticated status %d", resp.StatusCode)
	}
	resp.Body.Close()
	token := pairTest(t, srv.URL, "viewer")
	resp = apiRequest(t, "GET", root+"/status", token, "")
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = apiRequest(t, "GET", root+"/screenshot", token, "")
	image, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(image) != "jpeg-bytes" || resp.Header.Get("Cache-Control") != "no-store" || resp.Header.Get("X-Captured-At") == "" || resp.Header.Get("X-Request-ID") == "" {
		t.Fatalf("screenshot status=%d headers=%v bytes=%q", resp.StatusCode, resp.Header, image)
	}
	resp = apiRequest(t, "GET", root+"/screenshot/meta", token, "")
	var meta map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&meta)
	resp.Body.Close()
	if resp.StatusCode != 200 || meta["has_capture"] != true || meta["width"] != float64(1080) {
		t.Fatalf("metadata %+v", meta)
	}
	resp = apiRequest(t, "POST", srv.URL+"/api/v1/shares", token, "")
	var share struct {
		SharePath string `json:"share_path"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&share)
	resp.Body.Close()
	if resp.StatusCode != 201 || !strings.HasPrefix(share.SharePath, "/share/") {
		t.Fatalf("share status=%d path=%q", resp.StatusCode, share.SharePath)
	}
	resp = apiRequest(t, "GET", srv.URL+share.SharePath, "", "")
	sharedImage, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(sharedImage) != "jpeg-bytes" || resp.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("share read %d %q", resp.StatusCode, sharedImage)
	}
	resp = apiRequest(t, "GET", srv.URL+share.SharePath, "", "")
	if resp.StatusCode != 410 {
		t.Fatalf("reused share %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = apiRequest(t, "POST", root+"/actions", token, `{"request_id":"one","action":"tap","params":{"x":10,"y":20}}`)
	if resp.StatusCode != 403 || taps.Load() != 0 {
		t.Fatalf("viewer action status=%d taps=%d", resp.StatusCode, taps.Load())
	}
	resp.Body.Close()
	resp = apiRequest(t, "POST", srv.URL+"/api/v1/auth/revoke", token, "")
	if resp.StatusCode != 200 {
		t.Fatalf("revoke %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = apiRequest(t, "GET", root+"/status", token, "")
	if resp.StatusCode != 401 {
		t.Fatalf("revoked status %d", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestPairCodeSingleUseAndRateLimit(t *testing.T) {
	srv, conn, _ := testDevice(t, "viewer")
	defer srv.Close()
	defer conn.Close()
	_ = pairTest(t, srv.URL, "viewer")
	resp := apiRequest(t, "POST", srv.URL+"/api/v1/auth/pair", "", `{"device_id":"primary","code":"0123"}`)
	if resp.StatusCode != 401 {
		t.Fatalf("reused code %d", resp.StatusCode)
	}
	resp.Body.Close()
	for i, want := range []int{401, 429, 429} {
		resp = apiRequest(t, "POST", srv.URL+"/api/v1/auth/pair", "", `{"device_id":"primary","code":"9999"}`)
		if resp.StatusCode != want {
			t.Fatalf("attempt %d: got %d want %d", i, resp.StatusCode, want)
		}
		resp.Body.Close()
	}
}

func TestControllerActionIdempotency(t *testing.T) {
	srv, conn, taps := testDevice(t, "controller")
	defer srv.Close()
	defer conn.Close()
	token := pairTest(t, srv.URL, "controller")
	url := srv.URL + "/api/v1/devices/primary/actions"
	body := `{"request_id":"tap-001","action":"tap","params":{"x":10,"y":20}}`
	for i := 0; i < 2; i++ {
		resp := apiRequest(t, "POST", url, token, body)
		var result map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&result)
		resp.Body.Close()
		if resp.StatusCode != 200 || result["status"] != "executed" {
			t.Fatalf("action %d: status=%d result=%+v", i, resp.StatusCode, result)
		}
	}
	if taps.Load() != 1 {
		t.Fatalf("duplicate executed %d taps", taps.Load())
	}
	resp := apiRequest(t, "POST", url, token, `{"request_id":"tap-001","action":"home"}`)
	if resp.StatusCode != 409 {
		t.Fatalf("request conflict %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = apiRequest(t, "GET", srv.URL+"/api/v1/operations/tap-001", token, "")
	var result map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&result)
	resp.Body.Close()
	if resp.StatusCode != 200 || result["status"] != "executed" {
		t.Fatalf("operation status=%d result=%+v", resp.StatusCode, result)
	}
	resp = apiRequest(t, "POST", url, token, `{"request_id":"home-001","action":"home"}`)
	if resp.StatusCode != 200 {
		t.Fatalf("home status %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = apiRequest(t, "POST", url, token, `{"request_id":"after-home","action":"tap","params":{"x":10,"y":20}}`)
	if resp.StatusCode != 403 || taps.Load() != 1 {
		t.Fatalf("post-home control status=%d taps=%d", resp.StatusCode, taps.Load())
	}
	resp.Body.Close()
	resp = apiRequest(t, "GET", srv.URL+"/api/v1/devices/primary/status", token, "")
	var state map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&state)
	resp.Body.Close()
	if resp.StatusCode != 200 || state["control_available"] != false {
		t.Fatalf("suspended status %+v", state)
	}
}

func TestAuthExpirationAndDeviceScope(t *testing.T) {
	a := newAuthManager()
	token, hash, err := randomToken()
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	s := &session{tokenHash: hash, deviceID: "primary", scope: "viewer", createdAt: now,
		lastActive: now, windowStart: now}
	a.add(s)
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if _, code := a.authorize(req, "other", "viewer", false); code != 403 {
		t.Fatalf("cross device %d", code)
	}
	if _, code := a.authorize(req, "primary", "controller", false); code != 403 {
		t.Fatalf("viewer control %d", code)
	}
	s.lastActive = now.Add(-6 * time.Minute)
	if _, code := a.authorize(req, "primary", "viewer", false); code != 401 {
		t.Fatalf("idle expiry %d", code)
	}
	s.lastActive = now
	s.createdAt = now.Add(-31 * time.Minute)
	a.add(s)
	if _, code := a.authorize(req, "primary", "viewer", false); code != 401 {
		t.Fatalf("absolute expiry %d", code)
	}
}

func TestConcurrentPairAttemptsAreLimited(t *testing.T) {
	a := newAuthManager()
	var allowed atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if a.beginPair("203.0.113.1", "primary") {
				allowed.Add(1)
			}
		}()
	}
	wg.Wait()
	if allowed.Load() != 3 {
		t.Fatalf("allowed %d attempts", allowed.Load())
	}
	if !a.pairFailed("203.0.113.1", "primary") {
		t.Fatal("third failure did not lock pairing")
	}
	a.mu.Lock()
	a.attempts["ip:203.0.113.1"] = pairAttempts{count: 3, cooldownTo: time.Now().Add(-time.Second)}
	a.attempts["device:primary"] = pairAttempts{count: 3, cooldownTo: time.Now().Add(-time.Second)}
	a.mu.Unlock()
	if !a.beginPair("203.0.113.1", "primary") {
		t.Fatal("cooldown did not expire")
	}
}

func TestPublicDocumentationDoesNotExposeDevice(t *testing.T) {
	srv := httptest.NewServer(NewHandler(bridge.NewHub(), Config{DeviceToken: "d", MCPToken: "m"}))
	defer srv.Close()
	for _, path := range []string{"/", "/openapi.json"} {
		resp := apiRequest(t, "GET", srv.URL+path, "", "")
		if resp.StatusCode != 200 {
			t.Fatalf("%s status %d", path, resp.StatusCode)
		}
		resp.Body.Close()
	}
	resp := apiRequest(t, "GET", srv.URL+"/api/v1/devices/primary/status", "", "")
	if resp.StatusCode != 401 {
		t.Fatalf("unauthed API status %d", resp.StatusCode)
	}
	resp.Body.Close()
}
