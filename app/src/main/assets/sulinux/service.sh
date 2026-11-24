#!/system/bin/sh

case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR=$(pwd) ;;
esac
[ -f /data/adb/sulinux/active ] || exit 0

i=0
while [ $i -lt 6 ]; do
    if sh "$MODDIR/sulinux.sh" apply 2>/dev/null | grep -q SULINUX_APPLY_OK; then
        exit 0
    fi
    sleep 2
    i=$((i + 1))
done
