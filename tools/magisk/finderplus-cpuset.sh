#!/system/bin/sh
#
# finder+ CPU boost holder — Magisk service.d script.
#
# Install:
#   adb push finderplus-cpuset.sh /data/local/tmp/
#   adb shell su -c 'mkdir -p /data/adb/service.d && \
#     cp /data/local/tmp/finderplus-cpuset.sh /data/adb/service.d/ && \
#     chmod 755 /data/adb/service.d/finderplus-cpuset.sh'
#   (runs automatically from the next boot; start it now with
#    adb shell su -c 'nohup /data/adb/service.d/finderplus-cpuset.sh >/dev/null 2>&1 &')
#
# Remove:
#   adb shell su -c 'rm /data/adb/service.d/finderplus-cpuset.sh; pkill -f finderplus-cpuset'
#
# ---------------------------------------------------------------------------------------------------
# Why this exists
#
# Android assigns every process a cpuset, and a long-running background worker lands in one restricted
# to the little cores. On an Exynos 2400 that is the difference between cpu0-3 at 1.96 GHz and all ten
# cores including the 3.21 GHz Cortex-X4. Measured on the CLIP image encoder: 2320 ms/image confined to
# the little cores versus 190 ms/image on the full set — a 12x swing that has nothing to do with the
# model.
#
# The app already tries to move itself (see CpuBooster) and succeeds when granted root. The problem is
# durability: Samsung's power management re-classifies a sustained CPU load into its `moderate` or
# `abnormal` cpuset — both cpu0-3 — within a minute or two, and the app can only re-assert between work
# units. When one unit is a 50-second transcription, the demotion wins.
#
# An external holder does not have that limitation. It re-asserts on a timer regardless of what the app
# is in the middle of.
#
# ---------------------------------------------------------------------------------------------------
# Cost, stated plainly
#
# This keeps one app permanently on the big cores. That is a real battery and thermal cost, and it is
# deliberately opt-in rather than something the app does behind your back. The app's own thermal
# governor still applies, and it still stops at CRITICAL.

PKG=ai.rightone.finderplus
TARGET=/dev/cpuset/top-app
INTERVAL=5

# Bail out rather than spin forever if the kernel does not expose cpusets the way we expect.
[ -w "$TARGET/cgroup.procs" ] || { log -t finderplus-cpuset "no writable $TARGET; exiting"; exit 1; }

log -t finderplus-cpuset "holding $PKG in $(basename $TARGET) every ${INTERVAL}s"

while true; do
    PID=$(pidof "$PKG" 2>/dev/null)
    if [ -n "$PID" ]; then
        CUR=$(sed -n 's/^.*:cpuset:\(.*\)$/\1/p' "/proc/$PID/cgroup" 2>/dev/null)
        if [ "$CUR" != "/top-app" ]; then
            # The process first, then every thread: Android assigns cpusets per-thread, so moving only
            # the process leaves already-running ggml workers behind on the little cores.
            echo "$PID" > "$TARGET/cgroup.procs" 2>/dev/null
            for T in $(ls "/proc/$PID/task" 2>/dev/null); do
                echo "$T" > "$TARGET/tasks" 2>/dev/null
            done
            log -t finderplus-cpuset "moved $PID from ${CUR:-?} to /top-app"
        fi
    fi
    sleep "$INTERVAL"
done
