# Export request transport: public API compatibility

The request channel is a connected local stream socket pair. The parent sends bounded chunks;
only the renderer process performs blocking reads, bitmap/PDF work and bulk file writes.
There is no network listener, destination address, added permission, native library, reflection,
hidden-API exemption, minSdk change or lint suppression.

## Public API and ABI evidence

- `ParcelFileDescriptor.createSocketPair()` (the **zero-argument** overload) is public since
  [API 19](https://developer.android.com/reference/android/os/ParcelFileDescriptor#createSocketPair()).
- The **seven-argument** `Os.sendto(FileDescriptor, byte[], int, int, int, InetAddress, int)`
  is public since [API 21](https://developer.android.com/reference/android/system/Os#sendto(java.io.FileDescriptor,%20byte[],%20int,%20int,%20int,%20java.net.InetAddress,%20int)).
  The newer `SocketAddress` overload is not used.
- `MSG_DONTWAIT = 0x40` and `MSG_NOSIGNAL = 0x4000` are public Android NDK socket ABI
  constants. They are private named constants in this implementation, **not** accesses to
  nonexistent/hidden Java `OsConstants` fields. Both definitions are unconditional in the common
  `libc/include/sys/socket.h`, rather than selected by CPU ABI. Verified tagged public headers:
  [Android 8 / API 26](https://android.googlesource.com/platform/bionic/+/refs/tags/android-8.0.0_r1/libc/include/sys/socket.h),
  [Android 9 / API 28](https://android.googlesource.com/platform/bionic/+/refs/tags/android-9.0.0_r1/libc/include/sys/socket.h),
  [Android 10 / API 29](https://android.googlesource.com/platform/bionic/+/refs/tags/android-10.0.0_r1/libc/include/sys/socket.h),
  [Android 15 / API 35](https://android.googlesource.com/platform/bionic/+/refs/tags/android-15.0.0_r1/libc/include/sys/socket.h).
- The public socket-pair method constructs connected `AF_UNIX` / `SOCK_STREAM` descriptors:
  [API 26 framework source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/core/java/android/os/ParcelFileDescriptor.java).
- The native implementation of the older InetAddress `sendto` explicitly permits a null address,
  forwarding a null sockaddr and length zero for an already-connected socket. The same path was
  checked in the tagged [API 26](https://android.googlesource.com/platform/libcore/+/refs/tags/android-8.0.0_r1/luni/src/main/native/libcore_io_Linux.cpp),
  [API 28](https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r1/luni/src/main/native/libcore_io_Linux.cpp),
  [API 29](https://android.googlesource.com/platform/libcore/+/refs/tags/android-10.0.0_r1/luni/src/main/native/libcore_io_Linux.cpp),
  and [API 35](https://android.googlesource.com/platform/libcore/+/refs/tags/android-15.0.0_r1/luni/src/main/native/libcore_io_Linux.cpp) sources.
- The [Linux send contract](https://man7.org/linux/man-pages/man2/send.2.html) documents connected
  null-destination sending, per-call nonblocking behavior, and suppression of SIGPIPE while still
  reporting a closed peer as an error. Partial sends advance only by the returned count;
  EAGAIN/EINTR retry after a bounded backoff and another cancellation check.

Correction: the replaced `Os.fcntlInt` route is public only since API 30. Its execution on API 26
where the method happens to exist did not establish public-API compatibility, particularly for
Android 9/10 hidden-API enforcement. No such compatibility claim is retained.

## Ownership and behavior

The parent owns both socket descriptors until passing the read endpoint through Binder; its local
copy is then closed. The producer closes the write endpoint after flushing the complete request,
which signals EOF to the worker. Outer cleanup closes both endpoints on failure or cancellation.
The worker owns its received copy. It can still be terminated through the independent control
channel while blocked on a read or native renderer call. No structured parent job performs a
blocking send or waits for that native worker thread.

## Required validation matrix

Source/header inspection is complete. It is **not** a runtime compatibility result. Rebuild and
hash the candidate APKs before running this matrix; older artifacts do not validate this change.

1. Run `:feature:export:lintDebug`, `:feature:export:lintRelease`,
   `:feature:export:compileDebugAndroidTestKotlin`, and the existing export JVM suite. Keep
   NewApi checks enabled at minSdk 26. Include the normal release/R8 gate when integration is ready.
2. On API **26, 28, 29 and 35**, run `ExportProcessDeadlineDeviceTest` with default platform
   hidden-API enforcement (do not alter device policies). Record build fingerprint, API, ABI,
   revision/APK digest, instrumentation result and logcat.
3. Require the full-request backpressure/cancellation test, large Unicode request round trip
   with worker-observed EOF, closed-reader IOException with the main process alive (SIGPIPE
   suppression), real PDF/photo rendering, 30s stalled renderer, process-death and repeated retry
   cases to pass. Preserve exact failures and timing rather than broadening accepted exceptions.
4. Device tests are correctness evidence, not phone performance claims. A software emulator result
   on one API/ABI does not stand in for another matrix entry or a physical-device measurement.

## Revision-specific execution evidence

The exact `c9a322b` export APK passed all 15 export device tests on an isolated API26
software emulator. The warmed API35 export run at `88d788e` passed 8 of 15 tests
and failed 7 on timeouts. Emulator load is a possible contributor, not a demonstrated
cause or a compatibility pass. API28/API29 runtime results are not established here.

See the sixth and seventh checkpoints in
[implementation status](../../.scratch/lezi-reliability-remediation/implementation-status.md)
for the revision-specific context. These historical results do not establish the
full matrix or final-current-tree acceptance; later source changes require new
artifact pins and reruns. The remaining validation requirements above still apply.
