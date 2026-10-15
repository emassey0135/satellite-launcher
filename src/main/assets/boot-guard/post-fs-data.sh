#!/system/bin/sh
# Counts starts that don't finish (service.sh resets the count once one does). After three in a row, LSPosed is
# turned off, so the next start is as the device came.
DIR=/data/adb/satellite
mkdir -p $DIR
count=$(cat $DIR/unfinished-starts 2>/dev/null)
count=$(( ${count:-0} + 1 ))
if [ $count -ge 3 ]; then
  touch /data/adb/modules/zygisk_lsposed/disable
  echo "$(date): turned LSPosed off after $count starts that didn't finish" >> $DIR/boot-guard.log
  count=0
fi
echo $count > $DIR/unfinished-starts
