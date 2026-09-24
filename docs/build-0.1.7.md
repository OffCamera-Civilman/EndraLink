# EndraLink 0.1.7

Uses the approved cavern/tank Hydra illustration on Home, with native controls. fx-CG50 opens a separate circuit-themed workspace with the original microchip artwork. Home and Android Back return to the portal without dropping a live USB session. TI-Nspire, HP Prime and Donate remain disabled.

Eject now sends the SCSI ALLOW MEDIUM REMOVAL and START STOP UNIT (LOEJ=1, START=0) sequence for the observed Casio 07CF:6102, only with an existing storage session. Unsupported ALLOW is tolerated only for ILLEGAL REQUEST sense. Only exact zero-data eject commands are allowed; file writes remain blocked. Completion follows interface/handle cleanup, with explicit accepted/unconfirmed messages. An accepted command is not a hardware-verified exit from calculator USB mode. Use Android's system eject if the calculator stays in USB mode.

Reference implementation consulted: https://github.com/util-linux/util-linux/blob/master/sys-utils/eject.c (SCSI eject command framing). No source copied.

30 pure Java transport/FAT16 tests plus an Android activity navigation/render test cover protocol framing, failure handling, disabled future actions, and Home navigation. Real calculator eject behavior requires hardware testing and exported logs.
