#!/usr/bin/env bash
# Test aplikacji w emulatorze Androida 14: instalacja, logowanie, powiadomienia, dymek, połączenie.
APK="$1"; OUT="${2:-wyniki}"; mkdir -p "$OUT"
P=pl.micall.app
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
trig() { curl -s "http://127.0.0.1:8080/trigger?e=$1"; }
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
cat "$OUT/install.txt"
adb reverse tcp:8080 tcp:8080
adb shell cmd notification set_bubbles $P 1 >> "$OUT/install.txt" 2>&1
adb shell settings put secure notification_bubbles 1
adb logcat -c
adb shell am start -n $P/.MainActivity --es base http://127.0.0.1:8080
sleep 15
shot 01-start
trig msg1; sleep 5; shot 02-wiadomosc
trig msg2; sleep 3
trig grp; sleep 3
adb shell cmd statusbar expand-notifications; sleep 3; shot 03-powiadomienia; adb shell cmd statusbar collapse; sleep 1
adb shell input keyevent KEYCODE_HOME; sleep 2
trig msg1; sleep 4; shot 04-dymek-na-pulpicie
adb shell dumpsys notification --noredact > "$OUT/dumpsys-notification.txt"
trig call; sleep 5; shot 05-polaczenie
trig callend; sleep 3; shot 06-po-polaczeniu
trig missed; sleep 3
adb shell cmd statusbar expand-notifications; sleep 3; shot 07-nieodebrane; adb shell cmd statusbar collapse; sleep 1
adb shell input keyevent KEYCODE_SLEEP; sleep 3
trig call; sleep 7; shot 08-polaczenie-ekran-wylaczony
trig callend; sleep 2
adb shell input keyevent KEYCODE_WAKEUP; sleep 1; adb shell wm dismiss-keyguard; sleep 2
adb shell am start -n $P/.MainActivity; sleep 4
trig cmd-bubble; sleep 7; shot 09-dymek-z-aplikacji
trig clear; sleep 2
adb shell dumpsys activity services $P > "$OUT/services.txt"
adb shell dumpsys notification --noredact > "$OUT/dumpsys-notification-2.txt"
curl -s http://127.0.0.1:8080/log > "$OUT/mock-log.txt"
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "MiCall|AndroidRuntime|FATAL|$P" "$OUT/logcat-full.txt" > "$OUT/logcat.txt"
W=OK
grep -q Success "$OUT/install.txt" || W="BLAD instalacja"
grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | grep -q "$P" && W="BLAD awaria"
grep -q "STREAM device=" "$OUT/mock-log.txt" || W="BLAD brak-polaczenia"
echo "$W" > "$OUT/WYNIK"
cat "$OUT/WYNIK"
exit 0
