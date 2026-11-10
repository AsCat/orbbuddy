package api

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
)

var validRequestID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,80}$`)

type captureMeta struct {
	CapturedAt time.Time `json:"captured_at"`
	Connection time.Time `json:"-"`
	Width      int       `json:"width"`
	Height     int       `json:"height"`
	Revision   int64     `json:"revision"`
}

type operation struct {
	bodyHash [32]byte
	done     chan struct{}
	status   string
	result   json.RawMessage
	error    string
	code     int
}

type httpAPI struct {
	hub      *bridge.Hub
	auth     *authManager
	mu       sync.Mutex
	captures map[string]captureMeta
}

func newHTTPAPI(hub *bridge.Hub) *httpAPI {
	a := &httpAPI{hub: hub, auth: newAuthManager(), captures: make(map[string]captureMeta)}
	hub.SetEventHandler(func(id string, event bridge.Event) {
		if event.Name == "auth_revoke" {
			a.auth.revokeDevice(id)
			log.Printf("api device=%s event=authorization_revoked", id)
		}
	})
	return a
}

func (a *httpAPI) register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/auth/pair", a.pair)
	mux.HandleFunc("POST /api/v1/auth/revoke", a.revoke)
	mux.HandleFunc("GET /api/v1/devices/{deviceId}/status", a.status)
	mux.HandleFunc("GET /api/v1/devices/{deviceId}/screenshot", a.screenshot)
	mux.HandleFunc("GET /api/v1/devices/{deviceId}/screenshot/meta", a.screenshotMeta)
	mux.HandleFunc("POST /api/v1/devices/{deviceId}/actions", a.action)
	mux.HandleFunc("GET /api/v1/operations/{requestId}", a.operation)
	mux.HandleFunc("POST /api/v1/shares", a.createShare)
	mux.HandleFunc("GET /share/{token}", a.readShare)
}

func requestID() string {
	token, _, err := randomToken()
	if err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	return token[:32]
}

func jsonResponse(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func apiError(w http.ResponseWriter, status int, code, message string) {
	jsonResponse(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}

func decodeBody(r *http.Request, out any) error {
	defer r.Body.Close()
	decoder := json.NewDecoder(io.LimitReader(r.Body, 8<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return errors.New("unexpected extra JSON")
	}
	return nil
}

func (a *httpAPI) checkedSession(w http.ResponseWriter, r *http.Request, id, scope string, renew bool) (*session, bridge.DeviceStatus, bool) {
	if !validDeviceID.MatchString(id) {
		apiError(w, 404, "device_not_found", "device not found")
		return nil, bridge.DeviceStatus{}, false
	}
	s, code := a.auth.authorize(r, id, scope, renew)
	if code != 0 {
		message := "authorization required"
		if code == 403 {
			message = "insufficient permission or wrong device"
		}
		if code == 429 {
			message = "session rate limit exceeded"
		}
		apiError(w, code, "authorization_failed", message)
		return nil, bridge.DeviceStatus{}, false
	}
	status, known := a.hub.LookupStatus(id)
	if !known {
		apiError(w, 404, "device_not_found", "device not found")
		return nil, bridge.DeviceStatus{}, false
	}
	if status.Online && !status.ConnectedAt.Equal(s.connection) {
		a.auth.revokeDevice(id)
		apiError(w, 401, "session_expired", "device reconnected; authorize again")
		return nil, bridge.DeviceStatus{}, false
	}
	return s, status, true
}

func (a *httpAPI) pair(w http.ResponseWriter, r *http.Request) {
	var in struct {
		DeviceID string `json:"device_id"`
		Code     string `json:"code"`
	}
	if decodeBody(r, &in) != nil || !validDeviceID.MatchString(in.DeviceID) || len(in.Code) != 4 ||
		strings.Trim(in.Code, "0123456789") != "" {
		apiError(w, 400, "invalid_request", "device_id and four-digit code required")
		return
	}
	ip := clientIP(r)
	status, known := a.hub.LookupStatus(in.DeviceID)
	if !known {
		apiError(w, 404, "device_not_found", "device not found")
		return
	}
	if !status.Online || status.Paused {
		apiError(w, 503, "device_unavailable", "device offline or paused")
		return
	}
	if !a.auth.beginPair(ip, in.DeviceID) {
		apiError(w, 429, "pair_cooldown", "too many attempts; try again later")
		return
	}
	token, hash, err := randomToken()
	if err != nil {
		apiError(w, 500, "server_error", "could not create session")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	raw, err := a.hub.Call(ctx, in.DeviceID, "auth_pair", map[string]any{"code": in.Code})
	if err != nil {
		if errors.Is(err, bridge.ErrOffline) {
			apiError(w, 503, "device_offline", "device is offline")
			return
		}
		if errors.Is(err, context.DeadlineExceeded) {
			apiError(w, 504, "device_timeout", "device timed out")
			return
		}
		locked := a.auth.pairFailed(ip, in.DeviceID)
		log.Printf("api device=%s event=pair_failed ip=%s", in.DeviceID, ip)
		if locked {
			go a.notifyLockout(in.DeviceID)
			apiError(w, 429, "pair_cooldown", "too many attempts; try again later")
		} else {
			apiError(w, 401, "invalid_code", "invalid or expired pairing code")
		}
		return
	}
	var grant struct {
		Scope          string `json:"scope"`
		GrantID        string `json:"grantId"`
		AllowedPackage string `json:"allowedPackage"`
	}
	if json.Unmarshal(raw, &grant) != nil || (grant.Scope != "viewer" && grant.Scope != "controller") || len(grant.GrantID) < 20 {
		apiError(w, 502, "device_response", "invalid authorization response")
		return
	}
	now := time.Now()
	a.auth.add(&session{tokenHash: hash, deviceID: in.DeviceID, connection: status.ConnectedAt,
		grantID: grant.GrantID, scope: grant.Scope, createdAt: now, lastActive: now,
		windowStart: now, operations: make(map[string]*operation)})
	a.mu.Lock()
	delete(a.captures, in.DeviceID)
	a.mu.Unlock()
	a.auth.pairSucceeded(ip, in.DeviceID)
	log.Printf("api device=%s event=pair_success scope=%s ip=%s", in.DeviceID, grant.Scope, ip)
	jsonResponse(w, 200, map[string]any{"access_token": token, "token_type": "Bearer", "scope": grant.Scope,
		"device_id": in.DeviceID, "allowed_package": grant.AllowedPackage, "expires_at": now.Add(absoluteTTL).UTC(),
		"idle_timeout_seconds": int(idleTTL.Seconds())})
}

func (a *httpAPI) notifyLockout(id string) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, _ = a.hub.Call(ctx, id, "auth_lockout", map[string]any{})
}

func (a *httpAPI) revoke(w http.ResponseWriter, r *http.Request) {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		apiError(w, 401, "unauthorized", "authorization required")
		return
	}
	hash := sha256.Sum256([]byte(strings.TrimPrefix(header, "Bearer ")))
	a.auth.mu.Lock()
	s := a.auth.sessions[hash]
	if s == nil || expired(s, time.Now()) {
		a.auth.mu.Unlock()
		apiError(w, 401, "unauthorized", "authorization required")
		return
	}
	id := s.deviceID
	a.auth.mu.Unlock()
	a.auth.revokeDevice(id)
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	_, _ = a.hub.Call(ctx, id, "auth_revoke", map[string]any{})
	log.Printf("api device=%s event=authorization_revoked", id)
	jsonResponse(w, 200, map[string]any{"revoked": true})
}

func (a *httpAPI) status(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("deviceId")
	s, state, ok := a.checkedSession(w, r, id, "viewer", false)
	if !ok {
		return
	}
	a.mu.Lock()
	meta, captured := a.captures[id]
	a.mu.Unlock()
	var lastCapture any
	if captured && meta.Connection.Equal(state.ConnectedAt) {
		lastCapture = meta.CapturedAt
	}
	a.auth.mu.Lock()
	controlAvailable := s.scope == "controller" && !s.controlSuspended
	a.auth.mu.Unlock()
	jsonResponse(w, 200, map[string]any{"device_id": id, "online": state.Online,
		"last_seen_at": state.LastSeenAt, "paused": state.Paused,
		"screen_capture_available": state.Online && !state.Paused,
		"control_available":        state.Online && !state.Paused && controlAvailable,
		"last_capture_at":          lastCapture})
}

func (a *httpAPI) screenshot(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("deviceId")
	s, state, ok := a.checkedSession(w, r, id, "viewer", true)
	if !ok {
		return
	}
	if !state.Online || state.Paused {
		apiError(w, 503, "device_unavailable", "device offline or paused")
		return
	}
	if age := r.URL.Query().Get("max_age_ms"); age != "" {
		ms, err := strconv.Atoi(age)
		if err != nil || ms < 0 || ms > 60000 {
			apiError(w, 400, "invalid_max_age", "max_age_ms must be 0..60000")
			return
		}
	}
	a.captureScreenshot(w, r, id, s.grantID, state)
}

func (a *httpAPI) captureScreenshot(w http.ResponseWriter, r *http.Request, id, grantID string, state bridge.DeviceStatus) {
	reqID := requestID()
	w.Header().Set("X-Request-ID", reqID)
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	raw, err := a.hub.Call(ctx, id, "get_screen", map[string]any{"apiGrantId": grantID})
	if err != nil {
		deviceError(w, err)
		return
	}
	var screen screenResult
	if json.Unmarshal(raw, &screen) != nil || screen.Width <= 0 || screen.Height <= 0 {
		apiError(w, 502, "device_response", "invalid screenshot response")
		return
	}
	if screen.ImageBase64 == "" {
		message := screen.ImageError
		if message == "" {
			message = "phone did not provide a screenshot"
		}
		apiError(w, 409, "capture_unavailable", message)
		return
	}
	image, err := base64.StdEncoding.DecodeString(screen.ImageBase64)
	if err != nil {
		apiError(w, 502, "device_response", "invalid screenshot data")
		return
	}
	stamp := time.Now().UTC()
	if screen.CapturedAt != "" {
		if parsed, err := time.Parse(time.RFC3339Nano, screen.CapturedAt); err == nil {
			stamp = parsed.UTC()
		}
	}
	a.mu.Lock()
	a.captures[id] = captureMeta{CapturedAt: stamp, Connection: state.ConnectedAt, Width: screen.Width, Height: screen.Height, Revision: screen.Revision}
	a.mu.Unlock()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Captured-At", stamp.Format(time.RFC3339Nano))
	w.Header().Set("X-Screen-Width", strconv.Itoa(screen.Width))
	w.Header().Set("X-Screen-Height", strconv.Itoa(screen.Height))
	w.WriteHeader(200)
	_, _ = w.Write(image)
	log.Printf("api device=%s event=screenshot request=%s bytes=%d", id, reqID, len(image))
}

func (a *httpAPI) screenshotMeta(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("deviceId")
	_, state, ok := a.checkedSession(w, r, id, "viewer", false)
	if !ok {
		return
	}
	a.mu.Lock()
	meta, captured := a.captures[id]
	a.mu.Unlock()
	if !captured || !meta.Connection.Equal(state.ConnectedAt) {
		jsonResponse(w, 200, map[string]any{"device_id": id, "online": state.Online, "has_capture": false})
		return
	}
	jsonResponse(w, 200, map[string]any{"device_id": id, "online": state.Online,
		"has_capture": true, "captured_at": meta.CapturedAt, "width": meta.Width,
		"height": meta.Height, "revision": meta.Revision, "age_ms": time.Since(meta.CapturedAt).Milliseconds(),
		"fresh": state.Online && time.Since(meta.CapturedAt) < time.Minute})
}

func deviceError(w http.ResponseWriter, err error) {
	if errors.Is(err, bridge.ErrOffline) {
		apiError(w, 503, "device_offline", "device is offline")
		return
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		apiError(w, 504, "device_timeout", "device timed out")
		return
	}
	if strings.Contains(err.Error(), "paused") || strings.Contains(err.Error(), "revoked") {
		apiError(w, 403, "device_denied", "phone denied the request")
		return
	}
	apiError(w, 409, "device_rejected", err.Error())
}

func (a *httpAPI) action(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("deviceId")
	s, state, ok := a.checkedSession(w, r, id, "controller", true)
	if !ok {
		return
	}
	if !state.Online || state.Paused {
		apiError(w, 503, "device_unavailable", "device offline or paused")
		return
	}
	var in struct {
		RequestID string          `json:"request_id"`
		Action    string          `json:"action"`
		Params    json.RawMessage `json:"params"`
	}
	if decodeBody(r, &in) != nil || !validRequestID.MatchString(in.RequestID) {
		apiError(w, 400, "invalid_request", "valid request_id required")
		return
	}
	method, params, err := parseAction(in.Action, in.Params)
	if err != nil {
		apiError(w, 400, "invalid_action", err.Error())
		return
	}
	canonical, _ := json.Marshal(struct {
		Action string
		Params any
	}{in.Action, params})
	bodyHash := sha256.Sum256(canonical)
	a.auth.mu.Lock()
	op := s.operations[in.RequestID]
	if op != nil {
		a.auth.mu.Unlock()
		if op.bodyHash != bodyHash {
			apiError(w, 409, "request_id_conflict", "request_id already used for another action")
			return
		}
		select {
		case <-op.done:
			a.writeOperation(w, in.RequestID, op)
		case <-r.Context().Done():
			apiError(w, 504, "request_timeout", "request timed out")
		}
		return
	}
	if len(s.operations) >= 200 {
		a.auth.mu.Unlock()
		apiError(w, 429, "operation_limit", "too many operations in this session")
		return
	}
	op = &operation{bodyHash: bodyHash, done: make(chan struct{}), status: "accepted"}
	s.operations[in.RequestID] = op
	a.auth.mu.Unlock()
	params["apiGrantId"] = s.grantID
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	result, callErr := a.hub.Call(ctx, id, method, params)
	if callErr != nil {
		op.status, op.error, op.code = "failed", callErr.Error(), 409
		if errors.Is(callErr, bridge.ErrOffline) {
			op.code = 503
		}
		if errors.Is(callErr, context.DeadlineExceeded) {
			op.code = 504
		}
	} else {
		op.status, op.result, op.code = "executed", result, 200
		if method == "press_key" {
			a.auth.mu.Lock()
			s.controlSuspended = true
			a.auth.mu.Unlock()
		}
	}
	close(op.done)
	log.Printf("api device=%s event=action request=%s action=%s status=%s", id, in.RequestID, in.Action, op.status)
	a.writeOperation(w, in.RequestID, op)
}

func parseAction(action string, raw json.RawMessage) (string, map[string]any, error) {
	var p map[string]json.RawMessage
	if len(raw) > 0 && string(raw) != "null" {
		if json.Unmarshal(raw, &p) != nil {
			return "", nil, errors.New("params must be an object")
		}
	}
	if p == nil {
		p = map[string]json.RawMessage{}
	}
	getInt := func(key string) (int, error) {
		var value int
		if json.Unmarshal(p[key], &value) != nil {
			return 0, fmt.Errorf("%s must be an integer", key)
		}
		if value < 0 || value > 20000 {
			return 0, fmt.Errorf("%s outside allowed range", key)
		}
		return value, nil
	}
	switch action {
	case "tap":
		if len(p) != 2 {
			return "", nil, errors.New("tap requires x and y")
		}
		x, err := getInt("x")
		if err != nil {
			return "", nil, err
		}
		y, err := getInt("y")
		if err != nil {
			return "", nil, err
		}
		return "tap", map[string]any{"x": x, "y": y}, nil
	case "swipe":
		if len(p) != 5 {
			return "", nil, errors.New("swipe requires x1,y1,x2,y2,duration_ms")
		}
		out := map[string]any{}
		for _, key := range []string{"x1", "y1", "x2", "y2"} {
			value, err := getInt(key)
			if err != nil {
				return "", nil, err
			}
			out[key] = value
		}
		var duration int
		if json.Unmarshal(p["duration_ms"], &duration) != nil || duration < 50 || duration > 5000 {
			return "", nil, errors.New("duration_ms must be 50..5000")
		}
		out["durationMs"] = duration
		return "swipe", out, nil
	case "back", "home":
		if len(p) != 0 {
			return "", nil, errors.New("back and home take no params")
		}
		return "press_key", map[string]any{"key": action}, nil
	default:
		return "", nil, errors.New("unsupported action")
	}
}

func (a *httpAPI) writeOperation(w http.ResponseWriter, id string, op *operation) {
	response := map[string]any{"request_id": id, "status": op.status}
	if op.status == "executed" {
		response["result"] = json.RawMessage(op.result)
	}
	if op.status == "failed" {
		response["error"] = op.error
	}
	jsonResponse(w, op.code, response)
}

func (a *httpAPI) operation(w http.ResponseWriter, r *http.Request) {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		apiError(w, 401, "unauthorized", "authorization required")
		return
	}
	hash := sha256.Sum256([]byte(strings.TrimPrefix(header, "Bearer ")))
	a.auth.mu.Lock()
	s := a.auth.sessions[hash]
	if s == nil || expired(s, time.Now()) || s.scope != "controller" {
		a.auth.mu.Unlock()
		apiError(w, 401, "unauthorized", "authorization required")
		return
	}
	op := s.operations[r.PathValue("requestId")]
	a.auth.mu.Unlock()
	if state, known := a.hub.LookupStatus(s.deviceID); known && state.Online && !state.ConnectedAt.Equal(s.connection) {
		a.auth.revokeDevice(s.deviceID)
		apiError(w, 401, "session_expired", "device reconnected; authorize again")
		return
	}
	if op == nil {
		apiError(w, 404, "operation_not_found", "operation not found")
		return
	}
	select {
	case <-op.done:
		a.writeOperation(w, r.PathValue("requestId"), op)
	default:
		jsonResponse(w, 200, map[string]any{"request_id": r.PathValue("requestId"), "status": "accepted"})
	}
}

// A share URL is a single-use, read-only capability. No screenshot bytes are cached.
func (a *httpAPI) createShare(w http.ResponseWriter, r *http.Request) {
	s, code := a.auth.authorize(r, "", "viewer", true)
	if code != 0 {
		apiError(w, code, "authorization_failed", "valid viewer session required")
		return
	}
	state, known := a.hub.LookupStatus(s.deviceID)
	if !known || !state.Online || state.Paused {
		apiError(w, 503, "device_unavailable", "device offline or paused")
		return
	}
	if !state.ConnectedAt.Equal(s.connection) {
		a.auth.revokeDevice(s.deviceID)
		apiError(w, 401, "session_expired", "device reconnected")
		return
	}
	token, hash, err := randomToken()
	if err != nil {
		apiError(w, 500, "server_error", "could not create link")
		return
	}
	a.auth.mu.Lock()
	if s.shareCount >= 5 {
		a.auth.mu.Unlock()
		apiError(w, 429, "share_limit", "too many share links in this session")
		return
	}
	for key, grant := range a.auth.shares {
		if time.Now().After(grant.expiresAt) {
			delete(a.auth.shares, key)
		}
	}
	s.shareCount++
	expires := time.Now().Add(time.Minute)
	a.auth.shares[hash] = shareGrant{sessionHash: s.tokenHash, expiresAt: expires}
	a.auth.mu.Unlock()
	scheme := "https"
	if strings.HasPrefix(r.Host, "127.0.0.1:") || strings.HasPrefix(r.Host, "localhost:") {
		scheme = "http"
	}
	path := "/share/" + token
	jsonResponse(w, 201, map[string]any{"share_url": scheme + "://" + r.Host + path,
		"share_path": path, "expires_at": expires.UTC(), "single_use": true})
	log.Printf("api device=%s event=share_created", s.deviceID)
}

func (a *httpAPI) readShare(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	w.Header().Set("Referrer-Policy", "no-referrer")
	token := r.PathValue("token")
	if len(token) != 64 {
		apiError(w, 410, "share_expired", "share link expired or used")
		return
	}
	hash := sha256.Sum256([]byte(token))
	a.auth.mu.Lock()
	grant, exists := a.auth.shares[hash]
	delete(a.auth.shares, hash)
	s := a.auth.sessions[grant.sessionHash]
	valid := exists && time.Now().Before(grant.expiresAt) && s != nil && !expired(s, time.Now())
	a.auth.mu.Unlock()
	if !valid {
		apiError(w, 410, "share_expired", "share link expired or used")
		return
	}
	state, known := a.hub.LookupStatus(s.deviceID)
	if !known || !state.Online || state.Paused {
		apiError(w, 503, "device_unavailable", "device offline or paused")
		return
	}
	if !state.ConnectedAt.Equal(s.connection) {
		apiError(w, 410, "share_expired", "share link expired or used")
		return
	}
	a.captureScreenshot(w, r, s.deviceID, s.grantID, state)
}
