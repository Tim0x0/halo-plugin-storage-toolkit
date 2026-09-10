package com.timxs.storagetoolkit;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.imageio.PinnedImageIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageToolkitPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Mock
    Scheme scheme;

    @Mock
    PinnedImageIo pinnedImageIo;

    @InjectMocks
    StorageToolkitPlugin plugin;

    @Test
    void contextLoads() {
        // Mock schemeManager.get() 返回一个 Scheme 对象
        when(schemeManager.get(ProcessingLog.class)).thenReturn(scheme);
        
        plugin.start();
        plugin.stop();
    }
}
