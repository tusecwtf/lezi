package com.lezi.babylog.domain;

import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.concurrent.atomic.AtomicLong;

/** Counts actual SHA-256 finalizations reached from the production spool, not file stats. */
public final class SpoolHashCounter implements AutoCloseable {
    private static final AtomicLong COUNT = new AtomicLong();
    private static final String NAME = "LeziTestSpoolHashCounter";
    private final Provider provider;

    SpoolHashCounter() {
        provider = new Provider(NAME, "1.0", "isolated test spool hash observation") {};
        provider.put("MessageDigest.SHA-256", Digest.class.getName());
        if (Security.insertProviderAt(provider, 1) != 1) {
            throw new IllegalStateException("Could not install isolated test digest observer");
        }
        COUNT.set(0);
    }

    long count() { return COUNT.get(); }
    void reset() { COUNT.set(0); }

    @Override public void close() { Security.removeProvider(provider.getName()); }

    public static final class Digest extends MessageDigestSpi {
        private final MessageDigest delegate;
        public Digest() {
            try {
                delegate = MessageDigest.getInstance("SHA-256", "SUN");
            } catch (java.security.GeneralSecurityException error) {
                throw new IllegalStateException(error);
            }
        }
        @Override protected void engineUpdate(byte input) { delegate.update(input); }
        @Override protected void engineUpdate(byte[] input, int offset, int length) {
            delegate.update(input, offset, length);
        }
        @Override protected byte[] engineDigest() {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().startsWith("com.lezi.babylog.sync.media.FileImmutableMediaSpool")
                        || frame.getClassName().equals("com.lezi.babylog.sync.media.ImmutableMediaSpoolKt")) {
                    COUNT.incrementAndGet();
                    break;
                }
            }
            return delegate.digest();
        }
        @Override protected void engineReset() { delegate.reset(); }
        @Override protected int engineGetDigestLength() { return 32; }
    }
}
