package com.lezi.babylog.domain;

import com.lezi.babylog.sync.media.FileImmutableMediaSpool;
import com.lezi.babylog.sync.media.ImmutableMediaSpool;
import com.lezi.babylog.sync.media.SyncMediaFileStore;
import java.io.File;

/** Constructs the production spool across Kotlin's module-internal test boundary. */
final class RealServerMediaSpoolFactory {
    private RealServerMediaSpoolFactory() {}

    static ImmutableMediaSpool create(SyncMediaFileStore files, File root) {
        return new FileImmutableMediaSpool(files, root, 64L * 1024 * 1024,
                8L * 1024 * 1024, point -> {});
    }
}
