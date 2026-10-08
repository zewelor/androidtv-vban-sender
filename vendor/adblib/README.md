# Vendored AdbLib

These sources come from [cgutman/AdbLib at commit `d6937951eb98557c76ee2081e383d50886ce109a`](https://github.com/cgutman/AdbLib/tree/d6937951eb98557c76ee2081e383d50886ce109a). The upstream license is preserved in `LICENSE`.

`UPSTREAM-SHA256SUMS` records the exact downloaded source and license hashes before local changes. `SHA256SUMS` records the files as vendored here.

Four focused transport fixes support the bounded local shell adapter:

- `AdbStream.read()` drains payloads already queued before the peer's close packet.
- `AdbConnection.open()` checks the OKAY state before waiting, so an early OKAY cannot be lost; it also notices a dead reader thread while waiting for stream setup.
- `AdbProtocol` rejects negative and over-1 MiB payload lengths before allocating a payload array.
- `AdbProtocol` sizes OPEN destinations by their UTF-8 byte length, so non-ASCII service strings are encoded without truncation or buffer overflow.

The authentication test verifies the signature by applying the fixture public key to the received signature and comparing the decoded block to AdbLib's SHA-1 DigestInfo prefix followed by the 20-byte ADB token. AOSP calls `RSA_sign(NID_sha1, token, token_size, ...)` for ADB tokens ([AOSP implementation](https://android.googlesource.com/platform/system/core/%2B/android-cts-7.0_r18/adb/adb_auth_host.cpp)); `SHA1withRSA` is not the right verifier for this already-digested token format.
