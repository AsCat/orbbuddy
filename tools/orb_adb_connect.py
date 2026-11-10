#!/usr/bin/env python3
"""Use OrbBuddy to enable Android wireless debugging and connect local adb.

The script reads the MCP URL and token from ~/.config/orbbuddy/ by default.
It never prints the token. Running the script is an explicit request to open and,
when necessary, enable Android's Wireless debugging setting on the selected phone.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable


DEFAULT_URL_FILE = Path("~/.config/orbbuddy/mcp-url").expanduser()
DEFAULT_TOKEN_FILE = Path("~/.config/orbbuddy/mcp-token").expanduser()
DEFAULT_DEVICE_FILE = Path("~/.config/orbbuddy/default-device").expanduser()
ENDPOINT_RE = re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}(?!\d)")


class OrbError(RuntimeError):
    pass


class OrbClient:
    def __init__(self, url: str, token: str, device_id: str) -> None:
        self.url = url
        self.token = token
        self.device_id = device_id
        self.request_id = 0

    def call(self, tool: str, arguments: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        self.request_id += 1
        body = json.dumps(
            {
                "jsonrpc": "2.0",
                "id": self.request_id,
                "method": "tools/call",
                "params": {"name": tool, "arguments": arguments or {}},
            },
            ensure_ascii=False,
        ).encode()
        request = urllib.request.Request(
            self.url,
            data=body,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise OrbError(f"OrbBuddy request failed: {error}") from error
        if "error" in payload:
            raise OrbError(f"OrbBuddy MCP error: {payload['error'].get('message', 'unknown error')}")
        result = payload.get("result", {})
        content = result.get("content", [])
        for item in content:
            if item.get("type") == "text" and result.get("isError"):
                raise OrbError(item.get("text", "OrbBuddy tool failed"))
        return content

    def screen(self) -> dict[str, Any]:
        for attempt in range(3):
            content = self.call("get_screen", {"deviceId": self.device_id, "includeImage": False})
            for item in content:
                if item.get("type") != "text":
                    continue
                screen = json.loads(item["text"])
                if isinstance(screen.get("tree"), dict):
                    return screen
            if attempt < 2:
                time.sleep(0.35)
        raise OrbError("screen hierarchy is unavailable; wake and unlock the phone, then retry")

    def tap_node(self, node: dict[str, Any]) -> None:
        bounds = node.get("bounds") or {}
        left, top = int(bounds.get("left", -1)), int(bounds.get("top", -1))
        right, bottom = int(bounds.get("right", -1)), int(bounds.get("bottom", -1))
        if right <= left or bottom <= top:
            raise OrbError("target element has invalid bounds")
        self.call(
            "tap",
            {
                "deviceId": self.device_id,
                "x": (left + right) // 2,
                "y": (top + bottom) // 2,
            },
        )


def walk(node: dict[str, Any]) -> Iterable[dict[str, Any]]:
    yield node
    for child in node.get("children") or []:
        yield from walk(child)


def visible(node: dict[str, Any], width: int, height: int) -> bool:
    bounds = node.get("bounds") or {}
    left, top = bounds.get("left", -1), bounds.get("top", -1)
    right, bottom = bounds.get("right", -1), bounds.get("bottom", -1)
    return 0 <= left < right <= width and 0 <= top < bottom <= height


def node_text(node: dict[str, Any]) -> str:
    return " ".join(
        text.strip()
        for text in (str(node.get("text", "")), str(node.get("description", "")))
        if text.strip()
    )


def subtree_text(node: dict[str, Any]) -> str:
    return " ".join(filter(None, (node_text(item) for item in walk(node))))


def find_node(
    screen: dict[str, Any],
    predicate,
    *,
    clickable: bool | None = None,
) -> dict[str, Any] | None:
    width, height = int(screen["width"]), int(screen["height"])
    matches = []
    for node in walk(screen["tree"]):
        if not visible(node, width, height) or not node.get("enabled", True):
            continue
        if clickable is not None and bool(node.get("clickable")) != clickable:
            continue
        if predicate(node):
            bounds = node["bounds"]
            area = (bounds["right"] - bounds["left"]) * (bounds["bottom"] - bounds["top"])
            matches.append((area, node))
    return min(matches, key=lambda item: item[0])[1] if matches else None


def current_endpoint(screen: dict[str, Any]) -> str | None:
    match = ENDPOINT_RE.search(subtree_text(screen["tree"]))
    return match.group(0) if match else None


def is_wireless_detail(screen: dict[str, Any]) -> bool:
    text = subtree_text(screen["tree"])
    return "无线调试" in text and (
        "IP 地址和端口" in text
        or "使用配对码配对设备" in text
        or current_endpoint(screen) is not None
    )


def open_visible_wireless_row(client: OrbClient, screen: dict[str, Any]) -> dict[str, Any] | None:
    """Open the developer-options row without touching its trailing switch."""
    row = find_node(
        screen,
        lambda node: "无线调试" in subtree_text(node)
        and not node.get("className", "").endswith("Switch")
        and (node.get("bounds", {}).get("right", 0) - node.get("bounds", {}).get("left", 0))
        >= int(screen["width"]) // 3,
        clickable=True,
    )
    if row is None:
        return None
    client.tap_node(row)
    time.sleep(0.8)
    opened = client.screen()
    return opened if is_wireless_detail(opened) else None


def find_wireless_in_developer_options(
    client: OrbClient,
    screen: dict[str, Any],
) -> dict[str, Any] | None:
    """Handle Samsung builds that only expose the generic developer-options page."""
    text = subtree_text(screen["tree"])
    if "开发者选项" not in text:
        return None

    opened = open_visible_wireless_row(client, screen)
    if opened is not None:
        return opened

    width, height = int(screen["width"]), int(screen["height"])
    # Samsung remembers the developer-options scroll position. Move toward the
    # beginning first, then scan downward in short steps so the Wireless
    # debugging row cannot be skipped by a fling.
    x = max(width // 2, (width * 3) // 4)
    for _ in range(4):
        client.call(
            "swipe",
            {
                "deviceId": client.device_id,
                "x1": x,
                "y1": max(1, (height * 35) // 100),
                "x2": x,
                "y2": min(height - 1, (height * 82) // 100),
                "durationMs": 160,
            },
        )
        time.sleep(0.15)

    for _ in range(12):
        screen = client.screen()
        opened = open_visible_wireless_row(client, screen)
        if opened is not None:
            return opened
        client.call(
            "swipe",
            {
                "deviceId": client.device_id,
                "x1": x,
                "y1": (height * 62) // 100,
                "x2": x,
                "y2": (height * 50) // 100,
                "durationMs": 420,
            },
        )
        time.sleep(0.18)
    return None


def open_wireless_debugging(client: OrbClient) -> dict[str, Any]:
    try:
        client.call(
            "open_wireless_debugging_settings",
            {"deviceId": client.device_id},
        )
        time.sleep(1.0)
        screen = client.screen()
        if is_wireless_detail(screen):
            return screen
        samsung_screen = find_wireless_in_developer_options(client, screen)
        if samsung_screen is not None:
            return samsung_screen
    except OrbError:
        # OrbBuddy 0.4.1 and earlier do not expose the direct settings action.
        pass

    client.call("press_key", {"deviceId": client.device_id, "key": "home"})
    time.sleep(0.8)
    screen = client.screen()

    search = find_node(
        screen,
        lambda node: "搜索" in subtree_text(node) and len(subtree_text(node)) <= 12,
        clickable=True,
    )
    if search is None:
        raise OrbError("could not find the launcher search entry; unlock the phone and retry")
    client.tap_node(search)
    time.sleep(0.8)

    screen = client.screen()
    editor = find_node(screen, lambda node: bool(node.get("editable")))
    if editor is None:
        raise OrbError("launcher search did not expose an editable field")
    client.tap_node(editor)
    client.call("input_text", {"deviceId": client.device_id, "text": "无线调试"})
    time.sleep(1.2)

    screen = client.screen()
    result = find_node(
        screen,
        lambda node: "无线调试" in subtree_text(node) and "开发者选项" in subtree_text(node),
        clickable=True,
    )
    if result is None:
        raise OrbError("the system search did not return Wireless debugging")
    client.tap_node(result)
    time.sleep(1.2)
    return client.screen()


def ensure_enabled(client: OrbClient, screen: dict[str, Any]) -> tuple[dict[str, Any], str]:
    endpoint = current_endpoint(screen)
    if endpoint:
        return screen, endpoint

    switch = find_node(
        screen,
        lambda node: node.get("className", "").endswith("Switch")
        and ("无线调试" in subtree_text(node) or is_wireless_detail(screen)),
    )
    if switch is None:
        raise OrbError("Wireless debugging switch was not found")
    if switch.get("checked"):
        raise OrbError("Wireless debugging is enabled but no IP address is available; check Wi-Fi")

    switch_bar = find_node(
        screen,
        lambda node: str(node.get("viewId", "")).endswith("id/switch_bar"),
        clickable=True,
    )
    client.tap_node(switch_bar or switch)
    time.sleep(1.0)
    screen = client.screen()

    endpoint = current_endpoint(screen)
    if not endpoint:
        confirm = find_node(
            screen,
            lambda node: node_text(node) in {"允许", "确定", "开启"},
            clickable=True,
        )
        if confirm is not None:
            client.tap_node(confirm)
            time.sleep(1.0)
            screen = client.screen()
            endpoint = current_endpoint(screen)
    if not endpoint:
        raise OrbError("Wireless debugging did not expose an IP address and port")
    return screen, endpoint


def connect_adb(endpoint: str) -> None:
    result = subprocess.run(
        ["adb", "connect", endpoint],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=15,
        check=False,
    )
    if result.returncode != 0 or "failed" in result.stdout.lower():
        raise OrbError(f"adb connect failed: {result.stdout.strip()}")
    devices = subprocess.run(
        ["adb", "devices", "-l"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=10,
        check=True,
    ).stdout
    matching = next((line.strip() for line in devices.splitlines() if line.startswith(endpoint)), "")
    if " device " not in f" {matching} ":
        raise OrbError(f"adb did not report {endpoint} as ready")
    print(f"ADB connected: {matching}")


def load_token() -> str:
    token = os.environ.get("ORBBUDDY_MCP_TOKEN", "").strip()
    if token:
        return token
    token_file = Path(os.environ.get("ORBBUDDY_MCP_TOKEN_FILE", str(DEFAULT_TOKEN_FILE))).expanduser()
    try:
        return token_file.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise OrbError(f"cannot read OrbBuddy token file: {token_file}") from error


def load_url() -> str:
    url = os.environ.get("ORBBUDDY_MCP_URL", "").strip()
    if not url:
        url_file = Path(os.environ.get("ORBBUDDY_MCP_URL_FILE", str(DEFAULT_URL_FILE))).expanduser()
        try:
            url = url_file.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise OrbError(f"set ORBBUDDY_MCP_URL or create {url_file}") from error
    if not url.startswith("https://") or not url.endswith("/mcp"):
        raise OrbError("MCP URL must be https://.../mcp")
    return url


def default_device() -> str | None:
    env = os.environ.get("ORBBUDDY_DEFAULT_DEVICE", "").strip()
    if env:
        return env
    try:
        return DEFAULT_DEVICE_FILE.read_text(encoding="utf-8").strip() or None
    except FileNotFoundError:
        return None


def select_device(devices: list[dict[str, Any]], requested: str | None) -> str:
    online = [device for device in devices if device.get("online")]
    if requested:
        device = next((item for item in online if item.get("id") == requested), None)
        if device is None:
            raise OrbError(f"OrbBuddy device {requested!r} is offline; online: " +
                           ", ".join(str(item.get("id")) for item in online))
        if device.get("paused"):
            raise OrbError(f"OrbBuddy device {requested!r} is paused")
        return requested
    if len(online) != 1:
        ids = ", ".join(str(item.get("id")) for item in online) or "none"
        raise OrbError(f"specify --device or configure default-device; online devices: {ids}")
    if online[0].get("paused"):
        raise OrbError(f"OrbBuddy device {online[0]['id']!r} is paused")
    return str(online[0]["id"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", help="OrbBuddy device ID; overrides configured default")
    parser.add_argument("--no-adb", action="store_true", help="only enable and print the endpoint")
    parser.add_argument("--list", action="store_true", help="list connected phones without operating them")
    args = parser.parse_args()

    try:
        client = OrbClient(load_url(), load_token(), "")
        status_content = client.call("device_status")
        status_text = next((item.get("text", "") for item in status_content if item.get("type") == "text"), "[]")
        devices = json.loads(status_text)
        if args.list:
            for device in devices:
                print(f"{device.get('id')}  {device.get('manufacturer', '')} {device.get('model', '')}"
                      f"  {'paused' if device.get('paused') else 'online'}")
            return 0
        client.device_id = select_device(devices, args.device or default_device())

        try:
            screen = client.screen()
        except OrbError:
            screen = None
        if screen is None or not is_wireless_detail(screen):
            screen = open_wireless_debugging(client)
        _, endpoint = ensure_enabled(client, screen)
        print(f"Wireless debugging ready: {endpoint}")
        if not args.no_adb:
            connect_adb(endpoint)
        return 0
    except (OrbError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"orb-adb: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
