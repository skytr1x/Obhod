#!/system/bin/sh

MODULE_HOT_INSTALL_REQUEST=true
MODULE_HOT_RUN_SCRIPT=sulinux.sh

ui_print "- SULinux: установка ядерного обхода"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
for file in sulinux.sh service.sh uninstall.sh action.sh customize.sh; do
    [ -f "$MODPATH/$file" ] && set_perm "$MODPATH/$file" 0 0 0755
done
set_perm "$MODPATH/profile.json" 0 0 0644
set_perm "$MODPATH/config" 0 0 0644
