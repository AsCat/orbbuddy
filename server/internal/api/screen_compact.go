package api

import (
	"bytes"
	"encoding/json"
	"strings"
)

// compactNode contains only fields needed to select and describe a UI element.
// Android keeps the full tree so tap_element can still use the original path.
type compactNode struct {
	Path             string         `json:"path"`
	ClassName        string         `json:"className"`
	PackageName      string         `json:"packageName"`
	Text             string         `json:"text"`
	Description      string         `json:"description"`
	StateDescription string         `json:"stateDescription"`
	ViewID           string         `json:"viewId"`
	Clickable        bool           `json:"clickable"`
	Enabled          bool           `json:"enabled"`
	Checkable        bool           `json:"checkable"`
	Checked          bool           `json:"checked"`
	Editable         bool           `json:"editable"`
	Bounds           compactBounds  `json:"bounds"`
	Children         []*compactNode `json:"children"`
}

type compactBounds struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

func compactScreenJSON(screen screenResult) ([]byte, error) {
	var root *compactNode
	if len(screen.Tree) > 0 && !bytes.Equal(screen.Tree, []byte("null")) {
		if err := json.Unmarshal(screen.Tree, &root); err != nil {
			return nil, err
		}
	}
	elements := make([]map[string]any, 0)
	var visit func(*compactNode)
	visit = func(node *compactNode) {
		if node == nil {
			return
		}
		if node.Text != "" || node.Description != "" || node.StateDescription != "" ||
			node.Clickable || node.Checkable || node.Editable {
			element := map[string]any{
				"path":   node.Path,
				"bounds": [4]int{node.Bounds.Left, node.Bounds.Top, node.Bounds.Right, node.Bounds.Bottom},
			}
			if node.ClassName != "" {
				name := node.ClassName
				if i := strings.LastIndexByte(name, '.'); i >= 0 {
					name = name[i+1:]
				}
				element["role"] = name
			}
			if node.PackageName != "" && root != nil && node.PackageName != root.PackageName {
				element["packageName"] = node.PackageName
			}
			if node.Text != "" {
				element["text"] = node.Text
			}
			if node.Description != "" {
				element["description"] = node.Description
			}
			if node.StateDescription != "" {
				element["stateDescription"] = node.StateDescription
			}
			if node.ViewID != "" {
				element["viewId"] = node.ViewID
			}
			if node.Clickable {
				element["clickable"] = true
			}
			if node.Checkable {
				element["checkable"] = true
				element["checked"] = node.Checked
			}
			if node.Editable {
				element["editable"] = true
			}
			if !node.Enabled {
				element["enabled"] = false
			}
			elements = append(elements, element)
		}
		for _, child := range node.Children {
			visit(child)
		}
	}
	visit(root)
	meta := map[string]any{
		"revision":      screen.Revision,
		"width":         screen.Width,
		"height":        screen.Height,
		"treeMode":      "compact",
		"treeAvailable": root != nil,
		"elements":      elements,
	}
	if root != nil && root.PackageName != "" {
		meta["packageName"] = root.PackageName
	}
	if screen.ImageError != "" {
		meta["imageError"] = screen.ImageError
	}
	return json.Marshal(meta)
}
