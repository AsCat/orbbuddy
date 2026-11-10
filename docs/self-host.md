# 在自己的服务器部署圆伴

当前版本的异地移动网络模式需要一个手机和电脑都能访问的 HTTPS/WSS 中转入口。它可以运行在已有 VPS、云主机或其他有公网入口的机器上。普通家庭宽带、移动网络或公司网络若没有可入站地址，直接把电脑当公网服务器通常不可行；圆伴目前没有向其他用户开放的共享中转服务。

以下以一台具有公网域名、Ubuntu、systemd 和 Nginx 的主机为例。服务器只运行一个 Go 进程；截图与界面树不落盘。域名需先解析到服务器，80/443 端口需可从公网访问。维护者自己的 `nginx-orb.conf` 是实例配置；新安装请使用这里的模板。

## 1. 构建和安装 Go 服务

安装 [Go](https://go.dev/doc/install)（版本按 `server/go.mod`）以及 Nginx、Certbot、Git、OpenSSL。首次公开 Release 发布后可直接下载 Go 二进制；当前可从源码构建：

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot git openssl
git clone https://github.com/AsCat/orbbuddy.git
cd orbbuddy
(cd server && go test ./... && go build -o ../orbbuddy-server ./cmd/orbbuddy-server)
sudo install -d -m 755 /opt/orbbuddy
sudo install -m 755 orbbuddy-server /opt/orbbuddy/orbbuddy-server
```

生成两枚独立令牌。下面的命令不把令牌写入 shell 历史；`server.env` 仅供 root 和 systemd 服务读取。需要配置手机与 MCP 客户端时，用 `sudo cat /etc/orbbuddy/server.env` 在自己的终端查看，勿贴入公开 Issue。

```bash
sudo install -d -m 700 /etc/orbbuddy
sudo install -m 600 /dev/null /etc/orbbuddy/server.env
DEVICE_TOKEN=$(openssl rand -hex 32)
MCP_TOKEN=$(openssl rand -hex 32)
printf 'ORBBUDDY_DEVICE_TOKEN=%s\nORBBUDDY_MCP_TOKEN=%s\nORBBUDDY_LISTEN=127.0.0.1:18093\n' \
  "$DEVICE_TOKEN" "$MCP_TOKEN" | sudo tee /etc/orbbuddy/server.env >/dev/null
unset DEVICE_TOKEN MCP_TOKEN
sudo install -m 644 deploy/orbbuddy.service /etc/systemd/system/orbbuddy.service
sudo systemctl daemon-reload
sudo systemctl enable --now orbbuddy.service
```

## 2. 配置域名和证书

把 `example.com` 换成自己的域名。渲染脚本只替换域名与路径占位符，保留 Nginx 的 `$host` 等内建变量。它不会改动正在运行的站点；先检查输出再安装。已有 Nginx 站点时，请避免相同 `server_name` 冲突。

```bash
DOMAIN=example.com
sudo install -d -m 755 /var/www/letsencrypt/.well-known/acme-challenge
./deploy/render-nginx.sh "$DOMAIN" bootstrap > /tmp/orbbuddy-bootstrap.conf
sudo install -m 644 /tmp/orbbuddy-bootstrap.conf /etc/nginx/conf.d/orbbuddy.conf
sudo nginx -t
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/letsencrypt -d "$DOMAIN"
./deploy/render-nginx.sh "$DOMAIN" tls > /tmp/orbbuddy-tls.conf
sudo install -m 644 /tmp/orbbuddy-tls.conf /etc/nginx/conf.d/orbbuddy.conf
sudo nginx -t
sudo systemctl reload nginx
```

Certbot 的 webroot 流程参见[官方说明](https://eff-certbot.readthedocs.io/en/latest/using.html#webroot)。首次证书成功后，配置续期完成时重载 Nginx，并做一次续期演练：

```bash
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh >/dev/null
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
sudo certbot renew --dry-run
```

## 3. 接入与验收

在 Android App 填入 `wss://example.com/device`、设备 ID `primary` 和设备令牌，启用无障碍服务并开启测试会话。第二台手机填写相同 relay 地址及另一设备 ID，例如 `samsung`；当前单用户部署会共用设备令牌，不适合给互不信任的用户共用。电脑端 MCP URL 为 `https://example.com/mcp`，使用另一枚 MCP 令牌。首页 `https://example.com/` 提供说明和手机临时口令的只读网页查看器。

```bash
curl -fsS https://example.com/health
curl -I https://example.com/
sudo systemctl status orbbuddy.service
sudo journalctl -u orbbuddy.service -n 30 --no-pager
```

`/health` 只表示入口和 Go 服务存活，不表示手机在线。随后从 MCP 客户端调用 `device_status` 与 `get_screen`，再在手机暂停并确认读屏被拒绝。公网 `/mcp`、`/device` 和设备 API 在没有令牌时应返回 401。更多手机验收步骤见[快速开始](quickstart.md)。

## 升级与回滚

升级前备份当前二进制和 Nginx 配置。新二进制先在本机跑 Go 测试，再复制到 `/opt/orbbuddy/orbbuddy-server`；配置先执行 `nginx -t`，然后重载 Nginx，最后重启 Go 服务。Go 重启会清除临时 HTTPS 会话，手机应自动重连，用户需重新生成临时口令。出现问题时恢复备份并重启服务。不要把 `server.env` 或手机令牌放入备份日志、Git 仓库或 Release 资源。
