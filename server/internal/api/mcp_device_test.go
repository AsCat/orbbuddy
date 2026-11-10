package api

import (
	"encoding/json"
	"testing"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
	"github.com/modelcontextprotocol/go-sdk/mcp"
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
	content, err := screenContent(json.RawMessage(`{"revision":1,"width":1080,"height":2400,"tree":{"path":"0"}}`), false)
	if err != nil || len(content) != 1 {
		t.Fatalf("tree-only content = %d blocks, %v", len(content), err)
	}
}

func TestCompactScreenKeepsActionablePathsAndState(t *testing.T) {
	raw := json.RawMessage(`{"revision":42,"width":1080,"height":2400,"tree":{
		"path":"0","className":"android.widget.FrameLayout","packageName":"com.example","enabled":true,
		"children":[
			{"path":"0.0","className":"android.widget.LinearLayout","packageName":"com.example","enabled":true,
			 "children":[{"path":"0.0.0","className":"android.widget.TextView","packageName":"com.example",
			 "text":"Wireless debugging","enabled":true,"bounds":{"left":10,"top":20,"right":400,"bottom":80}}]},
			{"path":"0.1","className":"android.widget.Switch","packageName":"com.example",
			 "viewId":"com.example:id/wireless_switch","clickable":true,"checkable":true,"checked":false,
			 "enabled":true,"bounds":{"left":800,"top":20,"right":1000,"bottom":80}}
		]}}`)
	content, err := screenContent(raw, true)
	if err != nil || len(content) != 1 {
		t.Fatalf("compact content = %d blocks, %v", len(content), err)
	}
	text := content[0].(*mcp.TextContent).Text
	var result struct {
		Revision      int64            `json:"revision"`
		TreeMode      string           `json:"treeMode"`
		TreeAvailable bool             `json:"treeAvailable"`
		Elements      []map[string]any `json:"elements"`
	}
	if err := json.Unmarshal([]byte(text), &result); err != nil {
		t.Fatal(err)
	}
	if result.Revision != 42 || result.TreeMode != "compact" || !result.TreeAvailable || len(result.Elements) != 2 {
		t.Fatalf("unexpected compact metadata: %+v", result)
	}
	switchElement := result.Elements[1]
	if switchElement["path"] != "0.1" || switchElement["viewId"] != "com.example:id/wireless_switch" ||
		switchElement["checkable"] != true || switchElement["checked"] != false {
		t.Fatalf("switch path or state lost: %+v", switchElement)
	}
	if _, ok := switchElement["children"]; ok {
		t.Fatal("compact element unexpectedly contains a subtree")
	}
}

func TestCompactScreenReportsMissingTree(t *testing.T) {
	content, err := screenContent(json.RawMessage(`{"revision":3,"width":1080,"height":2400,"tree":null}`), true)
	if err != nil || len(content) != 1 {
		t.Fatalf("compact missing tree = %d blocks, %v", len(content), err)
	}
	var result struct {
		TreeAvailable bool             `json:"treeAvailable"`
		Elements      []map[string]any `json:"elements"`
	}
	if err := json.Unmarshal([]byte(content[0].(*mcp.TextContent).Text), &result); err != nil {
		t.Fatal(err)
	}
	if result.TreeAvailable || result.Elements == nil || len(result.Elements) != 0 {
		t.Fatalf("missing tree was not explicit: %+v", result)
	}
}
