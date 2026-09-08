package com.velocitypowered.proxy.network.discovery;

/** Version-one hub-position payload, independently validated before journalling or forwarding. */
public record HubPosition(String worldIdentity, String mapRevision, double x, double y, double z,
                          float yaw, float pitch, double velocityX, double velocityY, double velocityZ) {
  public HubPosition {
    new HandoffCapabilities(1, "hub-position", worldIdentity, mapRevision, false);
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
        || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984 || Math.abs(y) > 20_000_000
        || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90
        || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
        || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
      throw new IllegalArgumentException("Invalid hub-position payload");
    }
  }
}
