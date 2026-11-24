#!/system/bin/sh

case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR=$(pwd) ;;
esac
STATE=/data/adb/sulinux
CONFIG=$STATE/config
ACTIVE=$STATE/active
PIDFILE=$STATE/sulinux.pid
WATCHPID=$STATE/watch.pid
LOGFILE=$STATE/sulinux.log

PORT=10800
DROP_QUIC=1
APP_UID=0

if [ -f "$CONFIG" ]; then
    . "$CONFIG"
elif [ -f "$MODDIR/config" ]; then
    . "$MODDIR/config"
fi

BIN=$MODDIR/bin/sulinux
PROFILE=$MODDIR/profile.json
if [ ! -x "$BIN" ]; then
    BIN=$STATE/fallback/bin/sulinux
    PROFILE=$STATE/fallback/profile.json
fi

log() {
    echo "$*"
    [ -d "$STATE" ] && echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOGFILE" 2>/dev/null
}

daemon_alive() {
    [ -f "$PIDFILE" ] || return 1
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null
}

stop_daemon() {
    if [ -f "$PIDFILE" ]; then
        pid=$(cat "$PIDFILE" 2>/dev/null)
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null
            i=0
            while [ $i -lt 25 ] && kill -0 "$pid" 2>/dev/null; do
                sleep 0.2 2>/dev/null || sleep 1
                i=$((i + 1))
            done
            kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$PIDFILE"
    fi
}

start_daemon() {
    [ -x "$BIN" ] || {
        log "SULINUX_APPLY_FAIL missing daemon $BIN"
        return 1
    }
    stop_daemon
    mkdir -p "$STATE/cache"
    if [ -f "$LOGFILE" ]; then
        log_size=$(wc -c < "$LOGFILE" 2>/dev/null)
        [ -n "$log_size" ] && [ "$log_size" -gt 2097152 ] && : > "$LOGFILE"
    fi
    "$BIN" --daemon \
        --port "$PORT" \
        --profile "$PROFILE" \
        --cache "$STATE/cache" \
        --pid-file "$PIDFILE" \
        --log "$LOGFILE" || {
        log "SULINUX_APPLY_FAIL daemon exec"
        return 1
    }
    i=0
    while [ $i -lt 10 ]; do
        daemon_alive && return 0
        sleep 0.2 2>/dev/null || sleep 1
        i=$((i + 1))
    done
    log "SULINUX_APPLY_FAIL daemon did not stay up (log: $LOGFILE)"
    return 1
}

apply_nft() {
    command -v nft >/dev/null 2>&1 || return 1
    nft delete table inet sulinux 2>/dev/null
    nft add table inet sulinux || return 1
    nft add chain inet sulinux nat_out \
        '{ type nat hook output priority -100; policy accept; }' || return 1
    nft add chain inet sulinux filter_out \
        '{ type filter hook output priority -150; policy accept; }' || return 1

    nft add rule inet sulinux nat_out meta skuid 0 return || return 1
    nft add rule inet sulinux nat_out meta nfproto ipv4 tcp dport '{ 80, 443 }' \
        ip daddr '{ 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4 }' \
        return || return 1
    nft add rule inet sulinux nat_out meta nfproto ipv6 tcp dport '{ 80, 443 }' \
        ip6 daddr '{ ::1/128, fc00::/7, fe80::/10, ff00::/8 }' return || return 1
    nft add rule inet sulinux nat_out meta nfproto ipv4 tcp dport '{ 80, 443 }' \
        redirect to ":$PORT" || return 1
    nft add rule inet sulinux nat_out meta nfproto ipv6 tcp dport '{ 80, 443 }' \
        redirect to ":$PORT" || return 1

    nft add rule inet sulinux filter_out meta skuid 0 return || return 1
    if [ "$DROP_QUIC" = "1" ]; then
        nft add rule inet sulinux filter_out meta nfproto ipv4 udp dport 443 drop || return 1
        nft add rule inet sulinux filter_out meta nfproto ipv6 udp dport 443 drop || return 1
    fi
    return 0
}

apply_iptables() {
    any=0
    v4=0
    ok=1
    for cmd in iptables ip6tables; do
        command -v "$cmd" >/dev/null 2>&1 || continue
        any=1
        cmd_ok=1

        while "$cmd" -w -t nat -D OUTPUT -j SULINUX_NAT 2>/dev/null; do :; done
        while "$cmd" -w -t filter -D OUTPUT -j SULINUX_FILTER 2>/dev/null; do :; done
        "$cmd" -w -t nat -F SULINUX_NAT 2>/dev/null
        "$cmd" -w -t nat -X SULINUX_NAT 2>/dev/null
        "$cmd" -w -t filter -F SULINUX_FILTER 2>/dev/null
        "$cmd" -w -t filter -X SULINUX_FILTER 2>/dev/null

        "$cmd" -w -t nat -N SULINUX_NAT || { ok=0; continue; }
        "$cmd" -w -t nat -A OUTPUT -j SULINUX_NAT || { ok=0; continue; }
        "$cmd" -w -t nat -A SULINUX_NAT -m owner --uid-owner 0 -j RETURN || cmd_ok=0
        for net in 127.0.0.0/8 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 224.0.0.0/4; do
            "$cmd" -w -t nat -A SULINUX_NAT -p tcp -m multiport --dports 80,443 \
                -d "$net" -j RETURN || cmd_ok=0
        done
        if [ "$cmd" = "ip6tables" ]; then
            for net in ::1/128 fc00::/7 fe80::/10 ff00::/8; do
                "$cmd" -w -t nat -A SULINUX_NAT -p tcp -m multiport --dports 80,443 \
                    -d "$net" -j RETURN || cmd_ok=0
            done
        fi
        "$cmd" -w -t nat -A SULINUX_NAT -p tcp -m multiport --dports 80,443 \
            -j REDIRECT --to-ports "$PORT" || cmd_ok=0

        "$cmd" -w -t filter -N SULINUX_FILTER || { ok=0; continue; }
        "$cmd" -w -t filter -A OUTPUT -j SULINUX_FILTER || cmd_ok=0
        "$cmd" -w -t filter -A SULINUX_FILTER -m owner --uid-owner 0 -j RETURN || cmd_ok=0
        if [ "$DROP_QUIC" = "1" ]; then
            "$cmd" -w -t filter -A SULINUX_FILTER -p udp --dport 443 -j DROP || cmd_ok=0
        fi

        if [ "$cmd" = "iptables" ] && [ "$cmd_ok" = "1" ]; then
            v4=1
        fi
        [ "$cmd_ok" = "1" ] || ok=0
    done
    [ "$any" = "1" ] || return 1
    [ "$v4" = "1" ] || return 1
    [ "$ok" = "1" ] || log "SULINUX_WARN IPv6 rules incomplete"
    return 0
}

remove_rules() {
    nft delete table inet sulinux 2>/dev/null
    for cmd in iptables ip6tables; do
        command -v "$cmd" >/dev/null 2>&1 || continue
        while "$cmd" -w -t nat -D OUTPUT -j SULINUX_NAT 2>/dev/null; do :; done
        while "$cmd" -w -t filter -D OUTPUT -j SULINUX_FILTER 2>/dev/null; do :; done
        "$cmd" -w -t nat -F SULINUX_NAT 2>/dev/null
        "$cmd" -w -t nat -X SULINUX_NAT 2>/dev/null
        "$cmd" -w -t filter -F SULINUX_FILTER 2>/dev/null
        "$cmd" -w -t filter -X SULINUX_FILTER 2>/dev/null
    done
}

start_watch() {
    if [ -f "$WATCHPID" ] && kill -0 "$(cat "$WATCHPID" 2>/dev/null)" 2>/dev/null; then
        return 0
    fi
    rm -f "$WATCHPID"
    if command -v nohup >/dev/null 2>&1; then
        nohup sh "$MODDIR/sulinux.sh" watch >/dev/null 2>&1 &
    else
        sh "$MODDIR/sulinux.sh" watch >/dev/null 2>&1 &
    fi
    echo $! > "$WATCHPID"
}

watch() {
    log "SULINUX_WATCH_START"
    while [ -f "$ACTIVE" ]; do
        sleep 10
        [ -f "$ACTIVE" ] || break
        if ! daemon_alive; then
            start_daemon || {
                remove_rules
                break
            }
        fi
        if command -v nft >/dev/null 2>&1; then
            if ! nft list table inet sulinux >/dev/null 2>&1; then
                apply_nft || apply_iptables || remove_rules
            fi
        else
            if ! iptables -w -t nat -C OUTPUT -j SULINUX_NAT 2>/dev/null; then
                apply_iptables || remove_rules
            fi
        fi
    done
    rm -f "$WATCHPID"
    log "SULINUX_WATCH_STOP"
}

apply() {
    mkdir -p "$STATE/cache"
    : > "$ACTIVE"

    start_daemon || {
        rm -f "$ACTIVE"
        return 1
    }

    if command -v nft >/dev/null 2>&1 && apply_nft; then
        rules=nft
    else
        nft delete table inet sulinux 2>/dev/null
        if apply_iptables; then
            rules=iptables
        else
            stop_daemon
            remove_rules
            rm -f "$ACTIVE"
            log "SULINUX_APPLY_FAIL no usable nftables/iptables redirect"
            return 1
        fi
    fi

    start_watch
    log "SULINUX_APPLY_OK rules=$rules port=$PORT drop_quic=$DROP_QUIC"
    return 0
}

restore() {
    rm -f "$ACTIVE"
    if [ -f "$WATCHPID" ]; then
        kill "$(cat "$WATCHPID" 2>/dev/null)" 2>/dev/null
        rm -f "$WATCHPID"
    fi
    stop_daemon
    remove_rules
    sleep 1 2>/dev/null
    stop_daemon
    remove_rules
    log "SULINUX_RESTORE_OK"
    return 0
}

status() {
    if daemon_alive; then
        echo "SULINUX_STATUS running pid=$(cat "$PIDFILE")"
        if nft list table inet sulinux >/dev/null 2>&1; then
            echo "SULINUX_RULES nft"
        elif iptables -w -t nat -S SULINUX_NAT >/dev/null 2>&1; then
            echo "SULINUX_RULES iptables"
        else
            echo "SULINUX_RULES none"
        fi
    else
        echo "SULINUX_STATUS stopped"
    fi
}

case "${1:-apply}" in
    apply) apply ;;
    restore|stop) restore ;;
    status) status ;;
    watch) watch ;;
    *)
        echo "usage: sulinux.sh [apply|restore|status|watch]"
        exit 2
        ;;
esac
