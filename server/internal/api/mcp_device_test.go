package api

import (
	"encoding/json"
	"testing"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
)

func TestSelectDeviceRequiresExplicitIDWithMultiplePhones(t *testing.T) {
	devices := []bridge.DeviceStatus{{ID: "xiaomi"}, {ID: "samsung"}}
	if _, err := selectedID(devices, ""); err == nil {
		t.Fatal("ambiguous implicit device was accepted")
	}
	if got, err := selectedID(devices, "samsung"); err != nil || got != "samsung" {
		t.Fatalf("explicit Samsung selection = %q, %v", got, err)
	}
	if got, err := selectedID(devices[:1], ""); err != nil || got != "xiaomi" {
		t.Fatalf("sole connected device selection = %q, %v", got, err)
	}
}

func TestTreeOnlyScreenProducesNoImageContent(t *testing.T) {
	content, err := screenContent(json.RawMessage(`{"revision":1,"width":1080,"height":2400,"tree":{"path":"0"}}`))
	if err != nil || len(content) != 1 {
		t.Fatalf("tree-only content = %d blocks, %v", len(content), err)
	}
}
