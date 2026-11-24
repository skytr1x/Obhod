#!/system/bin/sh
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR=$(pwd) ;;
esac
sh "$MODDIR/sulinux.sh" restore >/dev/null 2>&1
rm -rf /data/adb/sulinux
