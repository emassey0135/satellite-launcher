#!/system/bin/sh
# Waits for the device to finish starting. If it hasn't after ten minutes (such as when the system keeps crashing
# and starting over without a full restart, which post-fs-data.sh doesn't see), LSPosed is turned off and the device
# restarts.
DIR=/data/adb/satellite
waited=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  if [ $waited -ge 600 ]; then
    touch /data/adb/modules/zygisk_lsposed/disable
    echo "$(date): turned LSPosed off after the device didn't finish starting in $waited seconds" >> $DIR/boot-guard.log
    echo 0 > $DIR/unfinished-starts
    reboot
    exit 0
  fi
  sleep 5
  waited=$(( waited + 5 ))
done
echo 0 > $DIR/unfinished-starts
