package api

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	idleTTL      = 5 * time.Minute
	absoluteTTL  = 30 * time.Minute
	pairCooldown = 5 * time.Minute
)

type session struct {
	tokenHash        [32]byte
	deviceID         string
	connection       time.Time
	grantID          string
	scope            string
	createdAt        time.Time
	lastActive       time.Time
	windowStart      time.Time
	windowCount      int
	operations       map[string]*operation
	shareCount       int
	controlSuspended bool
}

type shareGrant struct {
	sessionHash [32]byte
	expiresAt   time.Time
}

type pairAttempts struct {
	count      int
	cooldownTo time.Time
}

type authManager struct {
	mu       sync.Mutex
	sessions map[[32]byte]*session
	attempts map[string]pairAttempts
	shares   map[[32]byte]shareGrant
}

func newAuthManager() *authManager {
	return &authManager{sessions: make(map[[32]byte]*session), attempts: make(map[string]pairAttempts), shares: make(map[[32]byte]shareGrant)}
}

func randomToken() (string, [32]byte, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", [32]byte{}, err
	}
	token := hex.EncodeToString(raw[:])
	return token, sha256.Sum256([]byte(token)), nil
}

func (a *authManager) add(s *session) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now()
	for hash, existing := range a.sessions {
		if expired(existing, now) || existing.deviceID == s.deviceID {
			delete(a.sessions, hash)
		}
	}
	a.sessions[s.tokenHash] = s
}

func expired(s *session, now time.Time) bool {
	return now.Sub(s.createdAt) >= absoluteTTL || now.Sub(s.lastActive) >= idleTTL
}

func (a *authManager) authorize(r *http.Request, deviceID, scope string, renew bool) (*session, int) {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") || len(header) < len("Bearer ")+1 {
		return nil, http.StatusUnauthorized
	}
	hash := sha256.Sum256([]byte(strings.TrimPrefix(header, "Bearer ")))
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.sessions[hash]
	if s == nil {
		return nil, http.StatusUnauthorized
	}
	now := time.Now()
	if expired(s, now) {
		delete(a.sessions, hash)
		return nil, http.StatusUnauthorized
	}
	if (deviceID != "" && s.deviceID != deviceID) || (scope == "controller" && (s.scope != "controller" || s.controlSuspended)) {
		return nil, http.StatusForbidden
	}
	if now.Sub(s.windowStart) >= time.Minute {
		s.windowStart, s.windowCount = now, 0
	}
	s.windowCount++
	if s.windowCount > 60 {
		return nil, http.StatusTooManyRequests
	}
	if renew {
		s.lastActive = now
	}
	return s, 0
}

func (a *authManager) revokeDevice(deviceID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for hash, s := range a.sessions {
		if s.deviceID == deviceID {
			delete(a.sessions, hash)
		}
	}
}

// beginPair reserves an attempt before contacting the phone, so concurrent
// requests cannot all pass the limit before the first response arrives.
func (a *authManager) beginPair(ip, deviceID string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now()
	for _, key := range []string{"ip:" + ip, "device:" + deviceID} {
		attempt := a.attempts[key]
		if !attempt.cooldownTo.IsZero() && !now.Before(attempt.cooldownTo) {
			attempt = pairAttempts{}
			a.attempts[key] = attempt
		}
		if now.Before(attempt.cooldownTo) || attempt.count >= 3 {
			return false
		}
	}
	for _, key := range []string{"ip:" + ip, "device:" + deviceID} {
		attempt := a.attempts[key]
		if !attempt.cooldownTo.IsZero() && now.After(attempt.cooldownTo) {
			attempt = pairAttempts{}
		}
		attempt.count++
		a.attempts[key] = attempt
	}
	return true
}

func (a *authManager) pairFailed(ip, deviceID string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	locked := false
	for _, key := range []string{"ip:" + ip, "device:" + deviceID} {
		attempt := a.attempts[key]
		if attempt.count >= 3 {
			attempt.cooldownTo = time.Now().Add(pairCooldown)
			locked = true
		}
		a.attempts[key] = attempt
	}
	return locked
}

func (a *authManager) pairSucceeded(ip, deviceID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.attempts, "ip:"+ip)
	delete(a.attempts, "device:"+deviceID)
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if host == "127.0.0.1" || host == "::1" {
		if forwarded := r.Header.Get("X-Real-IP"); net.ParseIP(forwarded) != nil {
			return forwarded
		}
	}
	return host
}
