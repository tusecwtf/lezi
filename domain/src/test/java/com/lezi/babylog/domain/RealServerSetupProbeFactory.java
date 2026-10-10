package com.lezi.babylog.domain;

import com.lezi.babylog.sync.session.DefaultSetupHttpTransport;
import com.lezi.babylog.sync.session.DefaultTlsPeerInspector;
import com.lezi.babylog.sync.session.HttpSetupProbe;
import com.lezi.babylog.sync.session.SetupProbe;

/** Constructs the production setup probe across Kotlin's module-internal test boundary. */
final class RealServerSetupProbeFactory {
    private RealServerSetupProbeFactory() {}

    static SetupProbe create() {
        return new HttpSetupProbe(new DefaultSetupHttpTransport(), new DefaultTlsPeerInspector());
    }
}
