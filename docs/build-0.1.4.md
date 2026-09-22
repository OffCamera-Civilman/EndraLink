# EndraLink 0.1.4 — Hydra palette and read-only FAT16 browsing

## Changes

- Navy surfaces, cyan primary actions, electric-blue accents and silver text derived from the existing Hydra logo.
- Logo in the app header; original launcher logo retained.
- Insets for Android system bars and a scrolling layout.
- Browse calculator storage after USB permission and connection.
- FAT16 root/subfolder navigation, validated long filenames, file sizes and volume size.
- Disconnect cancels reads and releases EndraLink's interface and device handle.

## Safety and current limits

This build cannot create, modify, delete or copy calculator files. The transport has no sector-write API and rejects SCSI opcodes outside TEST UNIT READY, REQUEST SENSE, READ CAPACITY(10), and READ(10).

It supports 512-byte logical sectors, SCSI Bulk-Only transport, LUN 0, and either an unpartitioned FAT16 volume or one primary FAT16 MBR partition. It deliberately rejects FAT12, FAT32, exFAT, GPT, multiple FAT16 partitions, malformed metadata, out-of-bounds reads and invalid/looping cluster chains.

No USB driver is forcibly detached. A busy interface produces an error and closes the session. Close other USB applications or safely eject Android's mount before trying again. Disconnect in EndraLink releases this app's connection; it is not a system-wide filesystem eject.

The directory reader is capped at 4 MiB and 4096 entries; the preview displays the first 200 entries per folder and permits 32 folder levels. It does not open file contents yet.

## Automated validation

27 JUnit fixtures cover FAT16 root/subfolders, fragmented chains, volume labels, short and long names, deleted entries, MBR volumes, malformed geometry, bad/free/out-of-range/looping clusters, short sector reads, CBW/CSW framing, unit-attention handling, bounds, status failures, short responses and write-command rejection.

CI runs unit tests, APK assembly and Android lint. Hardware USB compatibility remains subject to testing on the fx-CG50 and Android device.

## Hardware test

1. Install 0.1.4 and check the navy/cyan UI and Hydra icon.
2. Connect the calculator in USB Flash mode. Select it and allow USB access.
3. Tap **Browse calculator**. Expect **FAT16 storage ready** and a directory listing.
4. Open a folder and use **Parent folder**. Tap a file to check its displayed size.
5. Disconnect, reconnect, then try unplugging during a read. The app should stay responsive and discard stale results.
6. If browsing fails, capture the complete status message and device identifiers. Do not format the calculator.

## Implementation references

- [USB-IF Bulk-Only Transport specification](https://www.usb.org/sites/default/files/usbmassbulk_10.pdf)
- [Microsoft FAT on-disk format specification](https://www.pcjs.org/documents/papers/microsoft/MS_FAT_OVERVIEW_103-2000-12-06.pdf)
- [Android USB device connection API](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
