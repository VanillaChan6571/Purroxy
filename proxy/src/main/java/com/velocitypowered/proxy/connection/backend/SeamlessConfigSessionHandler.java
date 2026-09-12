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

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Backend-only configuration handler; it has no client connection to forward packets to. */
final class SeamlessConfigSessionHandler implements MinecraftSessionHandler {
  private final MinecraftConnection backend;
  private final MinecraftSessionHandler next;
  private final SeamlessConfiguration negotiation;
  private final CompletableFuture<Void> result;
  private ScheduledFuture<?> timeout;
  private final java.util.function.BooleanSupplier valid;
  private final java.util.function.Supplier<CompletableFuture<Void>> approveArrival;
  private boolean finishing;

  SeamlessConfigSessionHandler(MinecraftConnection backend, MinecraftSessionHandler next,
      SeamlessConfiguration.Baseline baseline, CompletableFuture<Void> result) {
    this(backend, next, baseline, result, () -> true,
        () -> CompletableFuture.completedFuture(null));
  }

  SeamlessConfigSessionHandler(MinecraftConnection backend, MinecraftSessionHandler next,
      SeamlessConfiguration.Baseline baseline, CompletableFuture<Void> result, java.util.function.BooleanSupplier valid) {
    this(backend, next, baseline, result, valid, () -> CompletableFuture.completedFuture(null));
  }

  SeamlessConfigSessionHandler(MinecraftConnection backend, MinecraftSessionHandler next,
      SeamlessConfiguration.Baseline baseline, CompletableFuture<Void> result,
      java.util.function.BooleanSupplier valid,
      java.util.function.Supplier<CompletableFuture<Void>> approveArrival) {
    this.backend = backend;
    this.next = next;
    this.negotiation = new SeamlessConfiguration(baseline, backend.getProtocolVersion());
    this.result = result;
    this.valid = valid;
    this.approveArrival = approveArrival;
    result.whenComplete((ignored, failure) -> {
      if (result.isCancelled()) {
        backend.eventLoop().execute(() -> {
          if (timeout != null) {
            timeout.cancel(false);
          }
          backend.close();
        });
      }
    });
  }

  @Override
  public void activated() {
    if (result.isDone()) {
      backend.close();
      return;
    }
    timeout = backend.eventLoop().schedule(() -> fail(new IllegalStateException(
        "Detached configuration timed out")), 10, TimeUnit.SECONDS);
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    if (result.isDone() || finishing) {
      return;
    }
    try {
      if (!valid.getAsBoolean()) {
        throw new IllegalStateException("Client state changed during detached configuration");
      }
      MinecraftPacket response = negotiation.accept(packet).orElse(null);
      if (negotiation.complete()) {
        finishing = true;
        // Keep the backend in CONFIG until the destination has durably approved suppressing its
        // arrival sync. It cannot emit JoinGame before this acknowledgment is written.
        CompletableFuture<Void> approval = approveArrival.get();
        java.util.function.BiConsumer<Void, Throwable> finish = (ignored, approvalFailure) -> {
          if (result.isDone()) {
            return;
          } else if (approvalFailure != null) {
            fail(approvalFailure);
          } else if (!valid.getAsBoolean()) {
            fail(new IllegalStateException("Client state changed while approving seamless arrival"));
          } else {
            if (response != null) {
              backend.write(response);
            }
            backend.setActiveSessionHandler(StateRegistry.PLAY, next);
            result.complete(null);
          }
        };
        if (approval.isDone()) {
          // A completed approval is already running on this backend event loop (or is a test stub).
          approval.whenComplete(finish);
        } else {
          approval.whenCompleteAsync(finish, backend.eventLoop());
        }
      } else if (response != null) {
        backend.write(response);
      }
    } catch (RuntimeException failure) {
      fail(failure);
    }
  }

  @Override
  public void handleUnknown(ByteBuf packet) {
    if (finishing) {
      return;
    }
    fail(new IllegalStateException("Unknown packet during detached configuration"));
  }

  @Override
  public void exception(Throwable failure) {
    fail(failure);
  }

  @Override
  public void disconnected() {
    fail(new IllegalStateException("Backend disconnected during detached configuration"));
  }

  @Override
  public void deactivated() {
    if (timeout != null) {
      timeout.cancel(false);
    }
    if (!negotiation.complete() && !result.isDone()) {
      fail(new IllegalStateException("Detached configuration was superseded"));
    }
  }

  private void fail(Throwable failure) {
    if (timeout != null) {
      timeout.cancel(false);
    }
    if (result.completeExceptionally(failure)) {
      backend.close();
    }
  }
}
