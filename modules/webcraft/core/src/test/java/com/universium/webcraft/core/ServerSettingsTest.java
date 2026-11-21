package com.universium.webcraft.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerSettingsTest {
    @Test
    void networkAndSwaggerRequireExplicitConfiguration() {
        var settings = new ServerSettings();
        assertFalse(settings.listenerEnabled.getDefault());
        assertFalse(settings.singleplayerEnabled.getDefault());
        assertFalse(settings.singleplayerAllowNetwork.getDefault());
        assertFalse(settings.apiDocsEnabled.getDefault());
        assertEquals("127.0.0.1", settings.bindAddress.getDefault());
        assertEquals(ApiTransport.HTTP, settings.apiTransport.getDefault());
        assertEquals(ApiTransport.INHERIT, settings.onlinePlayersTransport.getDefault());
        assertEquals(ApiTransport.INHERIT, new ServerInfoSettings().transport.getDefault());
        assertEquals(ApiTransport.INHERIT, new ServerStatusSettings().transport.getDefault());
        assertFalse(settings.advancementsEnabled.getDefault());
        assertTrue(settings.advancementsLoopbackOnly.getDefault());
        assertEquals(ApiTransport.INHERIT, settings.advancementsTransport.getDefault());
    }
}
