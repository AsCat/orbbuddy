package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/AsCat/orbbuddy/server/internal/bridge"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type deviceInput struct {
	DeviceID string `json:"deviceId,omitempty" jsonschema:"Device ID; required when multiple devices are online"`
}

type screenInput struct {
	DeviceID     string `json:"deviceId,omitempty" jsonschema:"Device ID; required when multiple devices are online"`
	IncludeImage *bool  `json:"includeImage,omitempty" jsonschema:"Include a compressed JPEG; defaults to true"`
	MaxImageEdge int    `json:"maxImageEdge,omitempty" jsonschema:"Longest JPEG edge in pixels, 320 to 1280; defaults to 1280"`
}

type tapInput struct {
	DeviceID string `json:"deviceId,omitempty"`
	X        int    `json:"x"`
	Y        int    `json:"y"`
}

type tapElementInput struct {
	DeviceID string `json:"deviceId,omitempty"`
	Revision int64  `json:"revision"`
	Path     string `json:"path" jsonschema:"Element path from the latest get_screen result"`
}

type swipeInput struct {
	DeviceID   string `json:"deviceId,omitempty"`
	X1         int    `json:"x1"`
	Y1         int    `json:"y1"`
	X2         int    `json:"x2"`
	Y2         int    `json:"y2"`
	DurationMs int    `json:"durationMs"`
}

type textInput struct {
	DeviceID string `json:"deviceId,omitempty"`
	Text     string `json:"text"`
}

type keyInput struct {
	DeviceID string `json:"deviceId,omitempty"`
	Key      string `json:"key" jsonschema:"back or home"`
}

type appInfoInput struct {
	DeviceID    string `json:"deviceId,omitempty"`
	PackageName string `json:"packageName"`
}

func selectedID(devices []bridge.DeviceStatus, id string) (string, error) {
	if id != "" {
		return id, nil
	}
	if len(devices) > 1 {
		return "", errors.New("multiple devices online; specify deviceId from device_status")
	}
	if len(devices) == 1 {
		return devices[0].ID, nil
	}
	return "primary", nil
}

func toolError(err error) (*mcp.CallToolResult, any, error) {
	return &mcp.CallToolResult{IsError: true, Content: []mcp.Content{&mcp.TextContent{Text: err.Error()}}}, nil, nil
}

func callDevice(ctx context.Context, hub *bridge.Hub, id, method string, params any) (*mcp.CallToolResult, any, error) {
	selected, err := selectedID(hub.Status(), id)
	if err != nil {
		return toolError(err)
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	raw, err := hub.Call(ctx, selected, method, params)
	if err != nil {
		return toolError(err)
	}
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(raw)}}}, nil, nil
}

func NewMCPServer(hub *bridge.Hub) *mcp.Server {
	s := mcp.NewServer(&mcp.Implementation{Name: "orbbuddy", Version: "0.4.3"}, &mcp.ServerOptions{
		Instructions: "OrbBuddy is for Android development testing. Read the screen before element taps and read it again after the UI changes. Ask the user before entering sensitive data or taking a final order, purchase, or payment action; never treat an ordinary tap approval as payment authorization.",
	})
	mcp.AddTool(s, &mcp.Tool{Name: "device_status", Description: "List connected Android devices.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true}},
		func(context.Context, *mcp.CallToolRequest, struct{}) (*mcp.CallToolResult, any, error) {
			b, _ := json.Marshal(hub.Status())
			return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(b)}}}, nil, nil
		})
	mcp.AddTool(s, &mcp.Tool{Name: "get_screen", Description: "Read the current accessibility tree and optionally a compressed JPEG. Use includeImage=false for cheaper tree-only checks. Element paths are valid only for the returned revision.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true}},
		func(ctx context.Context, _ *mcp.CallToolRequest, in screenInput) (*mcp.CallToolResult, any, error) {
			selected, err := selectedID(hub.Status(), in.DeviceID)
			if err != nil {
				return toolError(err)
			}
			params := map[string]any{}
			if in.IncludeImage != nil {
				params["includeImage"] = *in.IncludeImage
			}
			if in.MaxImageEdge != 0 {
				params["maxImageEdge"] = in.MaxImageEdge
			}
			ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
			defer cancel()
			raw, err := hub.Call(ctx, selected, "get_screen", params)
			if err != nil {
				return toolError(err)
			}
			content, err := screenContent(raw)
			if err != nil {
				return toolError(fmt.Errorf("invalid screen response: %w", err))
			}
			return &mcp.CallToolResult{Content: content}, nil, nil
		})
	mcp.AddTool(s, &mcp.Tool{Name: "tap_element", Description: "Tap an element path from the latest get_screen result. Fails when the revision is stale."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in tapElementInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "tap_element", map[string]any{"revision": in.Revision, "path": in.Path})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "tap", Description: "Tap screen coordinates in the original device resolution returned by get_screen."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in tapInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "tap", map[string]any{"x": in.X, "y": in.Y})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "swipe", Description: "Swipe between screen coordinates."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in swipeInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "swipe", map[string]any{"x1": in.X1, "y1": in.Y1, "x2": in.X2, "y2": in.Y2, "durationMs": in.DurationMs})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "input_text", Description: "Replace the text of the currently focused editable field."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in textInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "input_text", map[string]any{"text": in.Text})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "press_key", Description: "Press Android back or home."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in keyInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "press_key", map[string]any{"key": in.Key})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "get_app_info", Description: "Read package metadata when Android package visibility permits it.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true}},
		func(ctx context.Context, _ *mcp.CallToolRequest, in appInfoInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "get_app_info", map[string]any{"packageName": in.PackageName})
		})
	mcp.AddTool(s, &mcp.Tool{Name: "open_wireless_debugging_settings", Description: "Open Android's Wireless debugging settings page. The caller must read the screen before changing the switch."},
		func(ctx context.Context, _ *mcp.CallToolRequest, in deviceInput) (*mcp.CallToolResult, any, error) {
			return callDevice(ctx, hub, in.DeviceID, "open_wireless_debugging_settings", map[string]any{})
		})
	return s
}
