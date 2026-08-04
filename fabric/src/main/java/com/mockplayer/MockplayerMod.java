package com.mockplayer;

import net.fabricmc.api.ModInitializer;

public class MockplayerMod implements ModInitializer {

    @Override
    public void onInitialize() {

        // This method is invoked by the Fabric mod loader when it is ready
        // to load your mod. You can access Fabric and Common code in this
        // project.
        Constants.LOG.info("Hello Fabric world!");
    }
}
