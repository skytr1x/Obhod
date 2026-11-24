#!/system/bin/sh
# kernelsu module action
case "$0" in
    */*) MODDIR=${0%/*} ;;
    *) MODDIR=$(pwd) ;;
esac
if [ -f /data/adb/sulinux/active ]; then
    sh "$MODDIR/sulinux.sh" restore
else
    sh "$MODDIR/sulinux.sh" apply
fi
