# EndraLink 0.1.5 testing build

- Adds persistent, bounded text diagnostics and an Export debug log button using Android's Save dialog. Export runs separately from USB reads.
- Fixes permission callback matching: permission broadcasts carry an endralink URI, so their receiver filter must accept that scheme. Detach broadcasts use a separate filter without a data scheme.
- Rechecks actual permission on resume and after 2/10 seconds; clears an unanswered request after 30 seconds so Connect can be retried. Request identity checks still reject stale callbacks.
- Clears stale connection details when a session closes.
- A failed interface claim on the observed Casio 07CF:6102 offers user-confirmed direct access. This may detach Android's storage driver; the dialog requires users to stop other USB apps and safely eject Android-mounted storage first. No automatic forced claim occurs. Storage commands remain read-only.
- Publishes a public GitHub Release APK and SHA-256 checksum only after tests, build, and lint pass on main. The README's latest-download link remains stable for subsequent versioned releases.
- Keeps the existing navy/cyan UI; illustrated cavern/Hydra screens remain future work.

Hardware behavior requires a real fx-CG50 and Android USB host device. If browsing fails, reproduce the failure and export the text log before reinstalling (uninstall removes private logs).
