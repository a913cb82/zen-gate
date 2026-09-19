# Break-glass (phone locked out of itself)

Zen Gate can block Settings, so normal uninstall paths may be gated. In order:

1. **In-app:** open Zen Gate (never gated) → Disable gate. Or pull the
   notification shade (SystemUI is never covered) → notification action.
2. **Wait it out:** every block ends; Open grants a session with full access.
3. **adb from PC** (bridge: android-development skill; values in `PORTABILITY.md`):
   ```bash
   adb shell pm disable-user --user 0 com.abrai.zengate  # stops the gate, keeps data
   adb uninstall com.abrai.zengate                       # nuclear option
   ```
4. **Reboot:** the gate restores itself (by design), so rebooting does NOT
   escape it — use 1–3.
