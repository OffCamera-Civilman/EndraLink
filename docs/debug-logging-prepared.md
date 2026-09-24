# Diagnostic logging — included in 0.1.5

The logging update is included in the user-authorized 0.1.5 build. See [build notes](build-0.1.5.md) for the accompanying connection fixes. Illustrated screens and file transfers remain future work.

## Recorded events

- UTC timestamp and monotonic uptime; app version, Android version and device model.
- Activity creation/resume/pause/destruction and button/entry taps.
- USB vendor/product IDs, interface/endpoint descriptors and permission state.
- Permission request identity, incoming callback identity, callback rejection reason, and permission observations after 2/10 seconds and on resume.
- Device opening, busy state, connection closing and visible status text.
- Interface-claim result, initialization/capacity/FAT16 stages, short USB transfers and exception traces.
- Directory entry counts and export results.

Local document names/URIs, device serial numbers, file contents and USB payloads are not deliberately recorded. Diagnostic USB bus paths and numeric file clusters are included. Logs stay in app-private storage; uninstalling the app deletes them.

The log rotates between two files of approximately 256 KiB each. Export joins the retained logs into a text file, using Android's Save dialog. No broad storage permission or network transfer is needed. Export runs independently of the USB worker so it remains usable while a USB operation is stuck.

## Hardware test checklist

1. Reproduce the permission hang, then choose Export debug log and save the .txt.
2. Verify the log distinguishes Android permission granted from callback received/accepted.
3. Reproduce the storage-busy error and verify USB_CLAIM_RESULT is present.
4. Exercise Back, denial, disconnect/unplug and reconnect.
5. Cancel export, then export again; verify no connection-state changes.
6. Confirm saved text opens, contains no selected document names/contents, and can be attached in chat.

The user must tap Export debug log and attach the saved text file themselves; the app does not send it automatically.
