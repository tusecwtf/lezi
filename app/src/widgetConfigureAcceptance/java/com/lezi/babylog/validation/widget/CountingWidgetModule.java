package com.lezi.babylog.validation.widget;

import com.lezi.babylog.feature.widget.WidgetStateModule;
import com.lezi.babylog.feature.widget.WidgetStateStore;
import dagger.Binds;
import dagger.Module;
import dagger.hilt.components.SingletonComponent;
import dagger.hilt.testing.TestInstallIn;

// Java can name the existing Kotlin-internal module's public JVM class without
// changing its production visibility. This source exists only in the opt-in test APK.
@Module
@TestInstallIn(components = SingletonComponent.class, replaces = WidgetStateModule.class)
public abstract class CountingWidgetModule {
    @Binds
    public abstract WidgetStateStore bindStore(CountingWidgetStateStore store);
}
