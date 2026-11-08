if ! $BOOTMODE; then
    abort "Install this module from KernelSU or Magisk."
fi

case ",$(getprop ro.product.cpu.abilist)," in
    *,arm64-v8a,*) ;;
    *) abort "MinClash requires ARM64 (arm64-v8a)." ;;
esac

if [ "$(getprop ro.build.version.sdk)" -lt 28 ]; then
    abort "MinClash requires Android 9 or newer."
fi

. "$MODPATH/root-env.sh"
[ -x "$BB" ] || abort "Root manager BusyBox unavailable: $BB"
if ! [ -x /system/bin/flock ] && ! has_applet flock; then
    abort "Neither Android nor the root manager provides flock."
fi

set_perm "$MODPATH/bin/mihomo" 0 0 0755
set_perm "$MODPATH/bin/socket-request" 0 0 0755
set_perm "$MODPATH/control.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
ui_print "Module installed. Reboot, then open MinClash to import a profile."
