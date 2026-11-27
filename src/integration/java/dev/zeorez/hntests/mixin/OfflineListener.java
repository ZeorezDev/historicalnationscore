package dev.zeorez.hntests.mixin;

import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.net.InetAddress;

/** Socketless dedicated-server fixture for offline save/restart tests. Excluded from the mod JAR. */
@Mixin(ServerConnectionListener.class)
abstract class OfflineListener {
    @Inject(method={"startTcpServerListener","m_9711_"}, at=@At("HEAD"), cancellable=true, remap=false)
    private void disableTestListener(InetAddress address, int port, CallbackInfo ci) {
        if (Boolean.getBoolean("hn.test.socketless")) ci.cancel();
    }
}
