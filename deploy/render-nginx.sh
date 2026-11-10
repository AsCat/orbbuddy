#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 <domain> <bootstrap|tls> [acme-root] [cert-dir]" >&2
    exit 2
}

[[ $# -ge 2 && $# -le 4 ]] || usage
domain=$1
mode=$2
acme_root=${3:-/var/www/letsencrypt}
cert_dir=${4:-/etc/letsencrypt/live/$domain}

[[ $domain =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]] || usage
[[ $acme_root =~ ^/[-A-Za-z0-9_./]+$ ]] || usage
[[ $cert_dir =~ ^/[-A-Za-z0-9_./]+$ ]] || usage

case $mode in
    bootstrap) template=nginx.bootstrap.conf.template ;;
    tls) template=nginx.conf.template ;;
    *) usage ;;
esac

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
content=$(<"$script_dir/$template")
content=${content//@@DOMAIN@@/$domain}
content=${content//@@ACME_ROOT@@/$acme_root}
content=${content//@@CERT_DIR@@/$cert_dir}
printf '%s\n' "$content"
