#!/usr/bin/env bash
# Claude Code Notification hook helper.
#
# Fires four signals in parallel when Claude wants user interaction:
#   1. systemMessage on stdout  -> styled banner ABOVE the Claude Code prompt,
#                                  rendered by the TUI itself so it cannot collide
#                                  with multi-choice permission prompts.
#   2. Terminal bell (\a)       -> IntelliJ JediTerm flashes its tab title.
#   3. Sound via paplay         -> audible cue via PulseAudio/PipeWire.
#   4. GNOME desktop toast      -> pops over IntelliJ regardless of focus (via gdbus).
#                                  Clicking the toast raises IntelliJ: a per-
#                                  notification listener waits (max 5 min) for
#                                  the ActionInvoked D-Bus signal, then runs
#                                  gtk-launch on the IntelliJ .desktop file.
#
# We deliberately do NOT write a visible banner to /dev/tty: the TUI owns the
# screen buffer and repaints on every permission prompt, which would overlap a
# raw-TTY banner with the answer options. systemMessage is the only channel
# that integrates with the TUI's layout.
#
# Reads Claude Code hook JSON on stdin, extracts .message, stays non-blocking
# so the Claude Code UI never stalls on a failing side channel.
set -u

INPUT=$(cat 2>/dev/null || true)

# Detect Stop vs Notification event.
# Stop hook JSON contains stop_hook_active:true and no .message field.
# Notification hook JSON contains a .message string.
IS_STOP=$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false' 2>/dev/null)

if [ "$IS_STOP" = "true" ]; then
  MSG="Claude hat die Aufgabe abgeschlossen"
else
  MSG=$(printf '%s' "$INPUT" | jq -r '.message // empty' 2>/dev/null)
  [ -z "$MSG" ] && MSG="Claude Code wartet auf deine Eingabe"
fi

# Classify the event type from the message so emoji/icon/sound match the
# kind of attention needed. Claude Code uses recognizable phrases:
#   "... needs your permission to use X" -> a permission prompt
#   "... waiting for your input"         -> an interactive question
# systemMessage is plain text (ANSI is stripped by the TUI), so the "color
# impression" is carried entirely by the emoji choice.
lower_msg="${MSG,,}"
case "$lower_msg" in
  *abgeschlossen*|*fertig*|*complete*)
    BANNER_EMOJI="✅✅✅"
    BANNER_LABEL="AUFGABE ABGESCHLOSSEN"
    TOAST_SUMMARY="✅ Claude ist fertig"
    TOAST_ICON="dialog-information"
    SOUND_FILE="/usr/share/sounds/freedesktop/stereo/complete.oga"
    ;;
  *permission*)
    BANNER_EMOJI="🔒🔒🔒"
    BANNER_LABEL="BERECHTIGUNG BENÖTIGT"
    TOAST_SUMMARY="🔒 Claude braucht eine Berechtigung"
    TOAST_ICON="dialog-password"
    SOUND_FILE="/usr/share/sounds/freedesktop/stereo/dialog-warning.oga"
    ;;
  *waiting*|*input*)
    BANNER_EMOJI="💬💬💬"
    BANNER_LABEL="AUSWAHL ERFORDERLICH"
    TOAST_SUMMARY="💬 Claude wartet auf deine Auswahl"
    TOAST_ICON="dialog-question"
    SOUND_FILE="/usr/share/sounds/freedesktop/stereo/complete.oga"
    ;;
  *)
    BANNER_EMOJI="🔔⚡🔔"
    BANNER_LABEL="CLAUDE WARTET"
    TOAST_SUMMARY="🔔 Claude Code wartet auf dich"
    TOAST_ICON="dialog-warning"
    SOUND_FILE="/usr/share/sounds/freedesktop/stereo/bell.oga"
    ;;
esac

# Primary channel: JSON on stdout -> Claude Code renders this as a system
# message placed above any prompt (including multi-choice permission prompts),
# because it participates in the TUI's own layout.
printf '%s' "$MSG" | jq -Rc \
  --arg emoji "$BANNER_EMOJI" \
  --arg label "$BANNER_LABEL" \
  '{systemMessage: ($emoji + "  " + $label + "  " + $emoji + "\n\n    ▶ " + . + "\n")}'

# All side channels run detached and fully silenced so a failing binary, closed
# TTY, or denied bus permission never corrupts the stdout JSON the parent parses.
{
  # BEL only, no visible text: the BEL byte (\a) is a C0 control character
  # that does not consume screen cells, so it does not collide with TUI
  # repaints -- but JediTerm still catches it and flashes the IDE tab title.
  # Wrapped in a subshell so redirection errors stay contained when pipe-testing.
  (printf '\a' > /dev/tty) 2>/dev/null || true

  # Audio cue via PulseAudio (paplay handles .oga/OGG; aplay can only do WAV).
  # Event-type-specific sound file chosen above.
  paplay "$SOUND_FILE" </dev/null >/dev/null 2>&1 || true

  # BEL + paplay handled above; gdbus runs OUTSIDE this background group
  # because we need its return value (the notification ID) to set up the
  # click-to-focus listener.
  :
} &
disown 2>/dev/null || true

# Ensure the D-Bus session bus is reachable.  Hook subprocesses often don't
# inherit DBUS_SESSION_BUS_ADDRESS from the terminal session.  On systemd
# systems (Ubuntu 20+, Fedora, Debian 12+) the socket is always at a fixed
# path under XDG_RUNTIME_DIR so we can reconstruct the address reliably.
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
  _RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${_RUNTIME_DIR}/bus"
fi

# GNOME / FreeDesktop toast via gdbus -- no libnotify-bin dependency needed.
# - urgency=2 (CRITICAL) keeps the toast visible until dismissed.
# - timeout=0 -> never auto-expire (subject to the notification daemon).
# - desktop-entry=jetbrains-idea groups the toast under IntelliJ in the tray
#   and shows the IDE icon.
# - actions: we register TWO actions:
#     * 'default' for daemons that honor body-click -> default action (e.g. dunst)
#     * 'focus-ide' as a named action -> GNOME Shell renders this as a
#       clickable button labelled "Zu IntelliJ wechseln". GNOME Shell 3.36+
#       does NOT invoke 'default' on body-click (body-click only dismisses),
#       so a named button action is required for GNOME users.
#   Our listener filters by notification ID, not action key -> both paths work.
INTELLIJ_DESKTOP_ID="jetbrains-idea"
NOTIFY_RAW=$(gdbus call --session \
  --dest=org.freedesktop.Notifications \
  --object-path=/org/freedesktop/Notifications \
  --method=org.freedesktop.Notifications.Notify \
  "Claude Code" 0 "$TOAST_ICON" \
  "$TOAST_SUMMARY" \
  "$MSG" \
  "[]" \
  "{'urgency': <byte 2>, 'category': <'im.received'>, 'desktop-entry': <'${INTELLIJ_DESKTOP_ID}'>}" \
  0 \
  </dev/null 2>/dev/null || true)

# Extract the uint32 notification ID from gdbus output: "(uint32 42,)"
NOTIFY_ID=$(printf '%s' "$NOTIFY_RAW" | grep -oE '[0-9]+' | head -n 1)

# Click-to-focus was intentionally removed: gtk-launch on the JetBrains Toolbox
# .desktop file opened a FRESH IntelliJ instance instead of focusing the
# running one, because the Toolbox launch script only checks for single-
# instance when a file argument is given. Re-enabling this feature requires
# a window-oriented focus call (wmctrl for XWayland, or a GNOME Shell
# extension for native Wayland) -- keep disabled until that path is wired.
:

exit 0
