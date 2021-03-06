/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.store;

import java.util.function.Consumer;
import tech.pegasys.teku.datastructures.forkchoice.MutablePrunableStore;
import tech.pegasys.teku.datastructures.forkchoice.PrunableStore;
import tech.pegasys.teku.datastructures.state.Checkpoint;
import tech.pegasys.teku.storage.api.StorageUpdateChannel;
import tech.pegasys.teku.util.async.SafeFuture;

public interface UpdatableStore extends PrunableStore {

  StoreTransaction startTransaction(final StorageUpdateChannel storageUpdateChannel);

  StoreTransaction startTransaction(
      final StorageUpdateChannel storageUpdateChannel, final StoreUpdateHandler updateHandler);

  void startMetrics();

  interface StoreTransaction extends MutablePrunableStore {
    SafeFuture<Void> commit();

    void commit(final Runnable onSuccess, final String errorMessage);

    default void commit(final Runnable onSuccess, final Consumer<Throwable> onError) {
      commit().finish(onSuccess, onError);
    }
  }

  interface StoreUpdateHandler {
    StoreUpdateHandler NOOP = finalizedCheckpoint -> {};

    void onNewFinalizedCheckpoint(Checkpoint finalizedCheckpoint);
  }
}
