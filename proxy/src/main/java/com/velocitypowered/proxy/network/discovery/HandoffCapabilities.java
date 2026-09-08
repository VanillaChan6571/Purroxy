package com.velocitypowered.proxy.network.discovery;

/** Explicit backend contract; discovery alone never implies transfer compatibility. */
public record HandoffCapabilities(int version, String profile, String worldIdentity,
                                  String mapRevision, boolean seamless) {
  public HandoffCapabilities {
    if (version != 1 || !"hub-position".equals(profile) || worldIdentity == null
        || !worldIdentity.matches("[a-z0-9][a-z0-9_-]{0,63}") || mapRevision == null
        || !mapRevision.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("Unsupported backend handoff capability");
    }
  }

  public boolean matches(HandoffCapabilities other) {
    return version == other.version && profile.equals(other.profile)
        && worldIdentity.equals(other.worldIdentity) && mapRevision.equals(other.mapRevision);
  }
}
