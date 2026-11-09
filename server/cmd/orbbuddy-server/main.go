package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/AsCat/orbbuddy/server/internal/api"
	"github.com/AsCat/orbbuddy/server/internal/bridge"
)

func main() {
	cfg := api.Config{DeviceToken: os.Getenv("ORBBUDDY_DEVICE_TOKEN"), MCPToken: os.Getenv("ORBBUDDY_MCP_TOKEN")}
	if cfg.DeviceToken == "" || cfg.MCPToken == "" {
		log.Fatal("ORBBUDDY_DEVICE_TOKEN and ORBBUDDY_MCP_TOKEN are required")
	}
	addr := os.Getenv("ORBBUDDY_LISTEN")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	srv := &http.Server{Addr: addr, Handler: api.NewHandler(bridge.NewHub(), cfg), ReadHeaderTimeout: 10 * time.Second}
	log.Printf("OrbBuddy listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}
