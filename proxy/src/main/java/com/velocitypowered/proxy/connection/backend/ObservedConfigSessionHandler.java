/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import io.netty.buffer.ByteBuf;

/** Captures a normal backend negotiation without changing its existing packet handling. */
final class ObservedConfigSessionHandler implements MinecraftSessionHandler {
  private final MinecraftSessionHandler delegate;
  private final VelocityServerConnection connection;

  ObservedConfigSessionHandler(MinecraftSessionHandler delegate, VelocityServerConnection connection) {
    this.delegate = delegate;
    this.connection = connection;
  }

  @Override
  public void activated() {
    connection.configurationCapture = new SeamlessConfiguration.Capture(connection.ensureConnected().getProtocolVersion());
    delegate.activated();
  }

  @Override
  public boolean beforeHandle() {
    return delegate.beforeHandle();
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    connection.configurationCapture.observe(packet);
    if (!packet.handle(delegate)) {
      delegate.handleGeneric(packet);
    }
  }

  @Override
  public void handleUnknown(ByteBuf packet) {
    connection.configurationCapture.invalidate();
    delegate.handleUnknown(packet);
  }

  @Override
  public void connected() {
    delegate.connected();
  }

  @Override
  public void deactivated() {
    delegate.deactivated();
  }

  @Override
  public void disconnected() {
    delegate.disconnected();
  }

  @Override
  public void exception(Throwable failure) {
    connection.configurationCapture.invalidate();
    delegate.exception(failure);
  }

  @Override
  public void writabilityChanged() {
    delegate.writabilityChanged();
  }

  @Override
  public void readCompleted() {
    delegate.readCompleted();
  }
}
