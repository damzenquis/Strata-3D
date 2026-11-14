# Security Policy

## Scope

Strata:3D has no internet permission and collects no data. Its main attack surface is **untrusted model files**:
a malicious STL, OBJ, PLY, 3MF, glTF, GLB or ZIP could try to crash the app, exhaust memory, or hang it.

Parsers must reject malformed input with a clear error. Crashes, hangs or excessive memory use caused by a
crafted file are security bugs.

## Supported versions

Only the latest release receives fixes.

## Reporting a vulnerability

Please **do not** open a public issue. Use GitHub's private vulnerability reporting
("Security" tab → "Report a vulnerability") on the project repository. Include the file that triggers the
problem, if you can share it, and the steps to reproduce.

You can expect an acknowledgement within 7 days. A fix is released as soon as it is ready, and you are credited
unless you prefer otherwise.
