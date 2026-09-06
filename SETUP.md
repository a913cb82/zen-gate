# Environment setup runbook (done 2026-09-06)

How the WSL → Xiaomi 15 Ultra adb bridge was built. Reproduce in order if rebuilt.

## Phone (Xiaomi 15 Ultra, HyperOS 3 / Android 16)

1. Settings → About phone → tap **HyperOS version 7×** (Developer options unlocked).
2. Settings → Additional settings → Developer options → enable:
   - **USB debugging**
   - **USB debugging (Security settings)** (needed for input/screencap; HyperOS may switch this off after a reboot — re-check if screenshots/input stop working while `adb devices` still shows `device`).
3. Plug into PC via data cable. On **Allow USB debugging?** tick **Always allow from this computer** → Allow. (Key lives in WSL `~/.android/adbkey`, so this survives reboots.)

## Windows (PowerShell)

`usbipd-win` was already installed (SELPHY setup). Otherwise: `winget install --exact dorssel.usbipd-win`.

```powershell
usbipd list                                     # find phone: 2717:ff88 "ADB Interface"
usbipd bind --busid 3-10                        # admin shell, once ever (persistent)
usbipd attach --wsl --hardware-id 2717:ff88 --auto-attach   # foreground loop; replug-test, then close window
```

Hidden logon task (each a single line, admin shell) — same pattern as `sandbox/printer/README.md`:

```powershell
$Action = New-ScheduledTaskAction -Execute "C:\Program Files\usbipd-win\usbipd.exe" -Argument "attach --wsl --hardware-id 2717:ff88 --auto-attach"; $Trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME; $Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable; Register-ScheduledTask -TaskName "usbipd Xiaomi ADB auto-attach" -Action $Action -Trigger $Trigger -Settings $Settings
Set-Content -Path "$env:USERPROFILE\usbipd-attach-phone.vbs" -Value 'CreateObject("Wscript.Shell").Run """C:\Program Files\usbipd-win\usbipd.exe"" attach --wsl --hardware-id 2717:ff88 --auto-attach", 0, False'; $A = New-ScheduledTaskAction -Execute "wscript.exe" -Argument "`"$env:USERPROFILE\usbipd-attach-phone.vbs`""; Set-ScheduledTask -TaskName "usbipd Xiaomi ADB auto-attach" -Action $A; Start-ScheduledTask -TaskName "usbipd Xiaomi ADB auto-attach"
```

Acceptance: `usbipd list` → `Attached`; `tasklist | findstr usbipd` → Services engine + hidden Console loop, no visible window. Requires a WSL terminal open at plug time.

## WSL (Ubuntu, systemd)

```bash
# platform-tools (no sudo needed)
cd ~ && curl -sO https://dl.google.com/android/repository/platform-tools-latest-linux.zip \
  && unzip -q -o platform-tools-latest-linux.zip
echo 'export PATH="$HOME/platform-tools:$PATH"' >> ~/.bashrc   # already done

# USB permissions for the Xiaomi VID (needs sudo; persistent via udev/systemd)
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="2717", MODE="0666", GROUP="plugdev"' \
  | sudo tee /etc/udev/rules.d/51-xiaomi.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=usb --attr-match=idVendor=2717
adb kill-server; adb devices                     # expect: <id>  device
```

Traps hit: fresh `adb devices` shows `no permissions` until the udev rule + server restart; shows `unauthorized` until the on-phone RSA prompt is allowed.

## Steady state

- After replug/reboot: nothing (logon task re-attaches). If it doesn't: `usbipd attach --wsl --busid <BUSID>`.
- Verify: `adb devices` → `c3c8b7bf  device`; `adb exec-out screencap -p > shot.png` works.
