package com.lezi.babylog.domain;

import com.lezi.babylog.sync.backend.SyncHttpException;
import com.lezi.babylog.sync.backend.retry.SyncRetryPolicyKt;

/** Reads the production HTTP failure across Kotlin's module-internal test boundary. */
final class RealServerHttpFailure {
    private RealServerHttpFailure() {}

    private static SyncHttpException find(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SyncHttpException) return (SyncHttpException) current;
        }
        return null;
    }

    static int statusCode(Throwable failure) {
        SyncHttpException http = find(failure);
        return http == null ? -1 : http.getStatusCode();
    }

    static String responseBody(Throwable failure) {
        SyncHttpException http = find(failure);
        return http == null ? "" : http.getResponseBody();
    }

    static Long retryAfterMillis(Throwable failure) {
        SyncHttpException http = find(failure);
        if (http == null || http.getRetryAfterHeader() == null) return null;
        return SyncRetryPolicyKt.parseRetryAfterMillis(http.getRetryAfterHeader(), System.currentTimeMillis());
    }
}
