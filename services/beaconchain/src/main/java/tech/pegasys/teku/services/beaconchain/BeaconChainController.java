/*
 * Copyright 2019 ConsenSys AG.
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

package tech.pegasys.teku.services.beaconchain;

import static com.google.common.primitives.UnsignedLong.ONE;
import static com.google.common.primitives.UnsignedLong.ZERO;
import static tech.pegasys.teku.core.ForkChoiceUtil.on_tick;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_epoch_at_slot;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_start_slot_at_epoch;
import static tech.pegasys.teku.logging.EventLogger.EVENT_LOG;
import static tech.pegasys.teku.logging.StatusLogger.STATUS_LOG;
import static tech.pegasys.teku.util.config.Constants.SECONDS_PER_SLOT;

import com.google.common.eventbus.EventBus;
import com.google.common.primitives.UnsignedLong;
import io.libp2p.core.crypto.KEY_TYPE;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.api.DataProvider;
import tech.pegasys.teku.beaconrestapi.BeaconRestApi;
import tech.pegasys.teku.core.BlockProposalUtil;
import tech.pegasys.teku.core.ForkChoiceUtil;
import tech.pegasys.teku.core.StateTransition;
import tech.pegasys.teku.core.operationvalidators.AttestationDataStateTransitionValidator;
import tech.pegasys.teku.core.operationvalidators.AttesterSlashingStateTransitionValidator;
import tech.pegasys.teku.core.operationvalidators.ProposerSlashingStateTransitionValidator;
import tech.pegasys.teku.core.operationvalidators.VoluntaryExitStateTransitionValidator;
import tech.pegasys.teku.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.datastructures.blocks.NodeSlot;
import tech.pegasys.teku.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.datastructures.operations.ProposerSlashing;
import tech.pegasys.teku.datastructures.operations.SignedVoluntaryExit;
import tech.pegasys.teku.events.EventChannels;
import tech.pegasys.teku.networking.eth2.Eth2Config;
import tech.pegasys.teku.networking.eth2.Eth2Network;
import tech.pegasys.teku.networking.eth2.Eth2NetworkBuilder;
import tech.pegasys.teku.networking.eth2.gossip.AttestationTopicSubscriber;
import tech.pegasys.teku.networking.eth2.mock.NoOpEth2Network;
import tech.pegasys.teku.networking.p2p.connection.TargetPeerRange;
import tech.pegasys.teku.networking.p2p.network.GossipConfig;
import tech.pegasys.teku.networking.p2p.network.NetworkConfig;
import tech.pegasys.teku.networking.p2p.network.WireLogsConfig;
import tech.pegasys.teku.pow.api.Eth1EventsChannel;
import tech.pegasys.teku.service.serviceutils.Service;
import tech.pegasys.teku.statetransition.OperationPool;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPool;
import tech.pegasys.teku.statetransition.attestation.AttestationManager;
import tech.pegasys.teku.statetransition.attestation.ForkChoiceAttestationProcessor;
import tech.pegasys.teku.statetransition.blockimport.BlockImporter;
import tech.pegasys.teku.statetransition.events.attestation.BroadcastAggregatesEvent;
import tech.pegasys.teku.statetransition.events.attestation.BroadcastAttestationEvent;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.genesis.GenesisHandler;
import tech.pegasys.teku.statetransition.util.FutureItems;
import tech.pegasys.teku.statetransition.util.PendingPool;
import tech.pegasys.teku.statetransition.util.StartupUtil;
import tech.pegasys.teku.storage.api.FinalizedCheckpointChannel;
import tech.pegasys.teku.storage.api.ReorgEventChannel;
import tech.pegasys.teku.storage.api.StorageQueryChannel;
import tech.pegasys.teku.storage.api.StorageUpdateChannel;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.storage.client.RecentChainData;
import tech.pegasys.teku.storage.client.StorageBackedRecentChainData;
import tech.pegasys.teku.storage.store.UpdatableStore.StoreTransaction;
import tech.pegasys.teku.sync.BlockManager;
import tech.pegasys.teku.sync.DefaultSyncService;
import tech.pegasys.teku.sync.FetchRecentBlocksService;
import tech.pegasys.teku.sync.SyncManager;
import tech.pegasys.teku.sync.SyncService;
import tech.pegasys.teku.sync.SyncStateTracker;
import tech.pegasys.teku.sync.util.NoopSyncService;
import tech.pegasys.teku.util.async.DelayedExecutorAsyncRunner;
import tech.pegasys.teku.util.async.SafeFuture;
import tech.pegasys.teku.util.cli.VersionProvider;
import tech.pegasys.teku.util.config.InvalidConfigurationException;
import tech.pegasys.teku.util.config.TekuConfiguration;
import tech.pegasys.teku.util.time.TimeProvider;
import tech.pegasys.teku.util.time.channels.SlotEventsChannel;
import tech.pegasys.teku.util.time.channels.TimeTickChannel;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.coordinator.BlockFactory;
import tech.pegasys.teku.validator.coordinator.DepositProvider;
import tech.pegasys.teku.validator.coordinator.Eth1DataCache;
import tech.pegasys.teku.validator.coordinator.Eth1VotingPeriod;
import tech.pegasys.teku.validator.coordinator.ValidatorApiHandler;

public class BeaconChainController extends Service implements TimeTickChannel {
  private static final Logger LOG = LogManager.getLogger();

  private final DelayedExecutorAsyncRunner asyncRunner = DelayedExecutorAsyncRunner.create();
  private final EventChannels eventChannels;
  private final MetricsSystem metricsSystem;
  private final TekuConfiguration config;
  private final TimeProvider timeProvider;
  private final EventBus eventBus;
  private final boolean setupInitialState;
  private final SlotEventsChannel slotEventsChannelPublisher;
  private final NodeSlot nodeSlot = new NodeSlot(ZERO);

  private volatile ForkChoice forkChoice;
  private volatile StateTransition stateTransition;
  private volatile BlockImporter blockImporter;
  private volatile RecentChainData recentChainData;
  private volatile Eth2Network p2pNetwork;
  private volatile BeaconRestApi beaconRestAPI;
  private volatile AggregatingAttestationPool attestationPool;
  private volatile DepositProvider depositProvider;
  private volatile SyncService syncService;
  private volatile AttestationManager attestationManager;
  private volatile CombinedChainDataClient combinedChainDataClient;
  private volatile Eth1DataCache eth1DataCache;
  private volatile OperationPool<AttesterSlashing> attesterSlashingPool;
  private volatile OperationPool<ProposerSlashing> proposerSlashingPool;
  private volatile OperationPool<SignedVoluntaryExit> voluntaryExitPool;

  private volatile UnsignedLong onTickSlotStart;
  private volatile UnsignedLong onTickSlotAttestation;
  private volatile UnsignedLong onTickSlotAggregate;
  private final UnsignedLong oneThirdSlotSeconds = UnsignedLong.valueOf(SECONDS_PER_SLOT / 3);

  private SyncStateTracker syncStateTracker;

  public BeaconChainController(
      TimeProvider timeProvider,
      EventBus eventBus,
      EventChannels eventChannels,
      MetricsSystem metricsSystem,
      TekuConfiguration config) {
    this.timeProvider = timeProvider;
    this.eventBus = eventBus;
    this.eventChannels = eventChannels;
    this.config = config;
    this.metricsSystem = metricsSystem;
    this.slotEventsChannelPublisher = eventChannels.getPublisher(SlotEventsChannel.class);
    this.setupInitialState = config.isInteropEnabled() || config.getInitialState() != null;
  }

  @Override
  protected SafeFuture<?> doStart() {
    this.eventBus.register(this);
    LOG.debug("Starting {}", this.getClass().getSimpleName());
    return initialize().thenCompose((__) -> SafeFuture.fromRunnable(beaconRestAPI::start));
  }

  private void startServices() {
    SafeFuture.allOfFailFast(
            attestationManager.start(),
            p2pNetwork.start(),
            syncService.start(),
            syncStateTracker.start())
        .reportExceptions();
  }

  @Override
  protected SafeFuture<?> doStop() {
    LOG.debug("Stopping {}", this.getClass().getSimpleName());
    return SafeFuture.allOf(
        SafeFuture.fromRunnable(() -> eventBus.unregister(this)),
        SafeFuture.fromRunnable(beaconRestAPI::stop),
        syncStateTracker.stop(),
        syncService.stop(),
        attestationManager.stop(),
        SafeFuture.fromRunnable(p2pNetwork::stop));
  }

  private SafeFuture<?> initialize() {
    return StorageBackedRecentChainData.create(
            metricsSystem,
            asyncRunner,
            eventChannels.getPublisher(StorageUpdateChannel.class),
            eventChannels.getPublisher(FinalizedCheckpointChannel.class),
            eventChannels.getPublisher(ReorgEventChannel.class),
            eventBus)
        .thenAccept(
            client -> {
              // Setup chain storage
              this.recentChainData = client;
              if (recentChainData.isPreGenesis()) {
                if (setupInitialState) {
                  setupInitialState();
                } else if (config.isEth1Enabled()) {
                  STATUS_LOG.loadingGenesisFromEth1Chain();
                } else {
                  throw new InvalidConfigurationException(
                      "ETH1 is disabled but initial state is unknown. Enable ETH1 or specify an initial state.");
                }
              }
              // Init other services
              this.initAll();
              eventChannels.subscribe(TimeTickChannel.class, this);

              recentChainData.subscribeStoreInitialized(this::onStoreInitialized);
              recentChainData.subscribeBestBlockInitialized(this::startServices);
            });
  }

  public void initAll() {
    initStateTransition();
    initForkChoice();
    initBlockImporter();
    initCombinedChainDataClient();
    initMetrics();
    initAttestationPool();
    initAttesterSlashingPool();
    initProposerSlashingPool();
    initVoluntaryExitPool();
    initEth1DataCache();
    initDepositProvider();
    initGenesisHandler();
    initAttestationManager();
    initP2PNetwork();
    initSyncManager();
    initSyncStateTracker();
    initValidatorApiHandler();
    initRestAPI();
  }

  private void initAttesterSlashingPool() {
    LOG.debug("BeaconChainController.initAttesterSlashingPool()");
    attesterSlashingPool =
        new OperationPool<>(AttesterSlashing.class, new AttesterSlashingStateTransitionValidator());
    blockImporter.subscribeToVerifiedBlockAttesterSlashings(attesterSlashingPool::removeAll);
  }

  private void initProposerSlashingPool() {
    LOG.debug("BeaconChainController.initProposerSlashingPool()");
    proposerSlashingPool =
        new OperationPool<>(ProposerSlashing.class, new ProposerSlashingStateTransitionValidator());
    blockImporter.subscribeToVerifiedBlockProposerSlashings(proposerSlashingPool::removeAll);
  }

  private void initVoluntaryExitPool() {
    LOG.debug("BeaconChainController.initVoluntaryExitPool()");
    voluntaryExitPool =
        new OperationPool<>(SignedVoluntaryExit.class, new VoluntaryExitStateTransitionValidator());
    blockImporter.subscribeToVerifiedBlockVoluntaryExits(voluntaryExitPool::removeAll);
  }

  private void initCombinedChainDataClient() {
    LOG.debug("BeaconChainController.initCombinedChainDataClient()");
    combinedChainDataClient =
        new CombinedChainDataClient(
            recentChainData, eventChannels.getPublisher(StorageQueryChannel.class));
  }

  private void initStateTransition() {
    LOG.debug("BeaconChainController.initStateTransition()");
    stateTransition = new StateTransition();
  }

  private void initForkChoice() {
    LOG.debug("BeaconChainController.initForkChoice()");
    forkChoice = new ForkChoice(recentChainData, stateTransition);
  }

  public void initMetrics() {
    LOG.debug("BeaconChainController.initMetrics()");
    eventChannels.subscribe(
        SlotEventsChannel.class, new BeaconChainMetrics(recentChainData, nodeSlot, metricsSystem));
  }

  public void initDepositProvider() {
    LOG.debug("BeaconChainController.initDepositProvider()");
    depositProvider = new DepositProvider(recentChainData, eth1DataCache);
    eventChannels
        .subscribe(Eth1EventsChannel.class, depositProvider)
        .subscribe(FinalizedCheckpointChannel.class, depositProvider);
  }

  private void initEth1DataCache() {
    LOG.debug("BeaconChainController.initEth1DataCache");
    eth1DataCache = new Eth1DataCache(new Eth1VotingPeriod());
  }

  private void initSyncStateTracker() {
    LOG.debug("BeaconChainController.initSyncStateTracker");
    syncStateTracker =
        new SyncStateTracker(
            asyncRunner,
            syncService,
            p2pNetwork,
            config.getStartupTargetPeerCount(),
            Duration.ofSeconds(config.getStartupTimeoutSeconds()));
  }

  public void initValidatorApiHandler() {
    LOG.debug("BeaconChainController.initValidatorApiHandler()");
    final BlockFactory blockFactory =
        new BlockFactory(
            new BlockProposalUtil(stateTransition),
            stateTransition,
            attestationPool,
            attesterSlashingPool,
            proposerSlashingPool,
            voluntaryExitPool,
            depositProvider,
            eth1DataCache,
            VersionProvider.getDefaultGraffiti());
    final AttestationTopicSubscriber attestationTopicSubscriber =
        new AttestationTopicSubscriber(p2pNetwork);
    final ValidatorApiHandler validatorApiHandler =
        new ValidatorApiHandler(
            combinedChainDataClient,
            syncStateTracker,
            stateTransition,
            blockFactory,
            attestationPool,
            attestationManager,
            attestationTopicSubscriber,
            eventBus);
    eventChannels
        .subscribe(SlotEventsChannel.class, attestationTopicSubscriber)
        .subscribe(ValidatorApiChannel.class, validatorApiHandler);
  }

  private void initGenesisHandler() {
    if (setupInitialState) {
      return;
    }
    LOG.debug("BeaconChainController.initPreGenesisDepositHandler()");
    eventChannels.subscribe(Eth1EventsChannel.class, new GenesisHandler(recentChainData));
  }

  private void initAttestationManager() {
    final PendingPool<ValidateableAttestation> pendingAttestations =
        PendingPool.createForAttestations();
    final FutureItems<ValidateableAttestation> futureAttestations =
        new FutureItems<>(ValidateableAttestation::getEarliestSlotForForkChoiceProcessing);
    final ForkChoiceAttestationProcessor forkChoiceAttestationProcessor =
        new ForkChoiceAttestationProcessor(recentChainData, forkChoice);
    attestationManager =
        AttestationManager.create(
            eventBus,
            pendingAttestations,
            futureAttestations,
            forkChoiceAttestationProcessor,
            attestationPool);
    eventChannels
        .subscribe(SlotEventsChannel.class, attestationManager)
        .subscribe(FinalizedCheckpointChannel.class, pendingAttestations);
  }

  public void initP2PNetwork() {
    LOG.debug("BeaconChainController.initP2PNetwork()");
    if (!config.isP2pEnabled()) {
      this.p2pNetwork = new NoOpEth2Network();
    } else {
      final Optional<Bytes> bytes = getP2pPrivateKeyBytes();
      final PrivKey pk =
          bytes.isEmpty()
              ? KeyKt.generateKeyPair(KEY_TYPE.SECP256K1).component1()
              : KeyKt.unmarshalPrivateKey(bytes.get().toArrayUnsafe());
      final NetworkConfig p2pConfig =
          new NetworkConfig(
              pk,
              config.getP2pInterface(),
              config.getP2pAdvertisedIp(),
              config.getP2pPort(),
              config.getP2pAdvertisedPort(),
              config.getP2pStaticPeers(),
              config.isP2pDiscoveryEnabled(),
              config.getP2pDiscoveryBootnodes(),
              new TargetPeerRange(config.getP2pPeerLowerBound(), config.getP2pPeerUpperBound()),
              GossipConfig.DEFAULT_CONFIG,
              new WireLogsConfig(
                  config.isLogWireCipher(),
                  config.isLogWirePlain(),
                  config.isLogWireMuxFrames(),
                  config.isLogWireGossip()));
      final Eth2Config eth2Config = new Eth2Config(config.isP2pSnappyEnabled());

      this.p2pNetwork =
          Eth2NetworkBuilder.create()
              .config(p2pConfig)
              .eth2Config(eth2Config)
              .eventBus(eventBus)
              .recentChainData(recentChainData)
              .gossipedAttestationConsumer(
                  attestation ->
                      attestationManager
                          .onAttestation(attestation)
                          .ifInvalid(
                              reason -> LOG.debug("Rejected gossiped attestation: " + reason)))
              .gossipedAttesterSlashingConsumer(attesterSlashingPool::add)
              .gossipedProposerSlashingConsumer(proposerSlashingPool::add)
              .gossipedVoluntaryExitConsumer(voluntaryExitPool::add)
              .processedAttestationSubscriptionProvider(
                  attestationManager::subscribeToProcessedAttestations)
              .verifiedBlockAttestationsProvider(
                  blockImporter::subscribeToVerifiedBlockAttestations)
              .historicalChainData(eventChannels.getPublisher(StorageQueryChannel.class))
              .metricsSystem(metricsSystem)
              .timeProvider(timeProvider)
              .asyncRunner(asyncRunner)
              .build();
    }
  }

  private Optional<Bytes> getP2pPrivateKeyBytes() {
    final Optional<Bytes> bytes;
    final String p2pPrivateKeyFile = config.getP2pPrivateKeyFile();
    if (p2pPrivateKeyFile != null) {
      try {
        bytes = Optional.of(Bytes.fromHexString(Files.readString(Paths.get(p2pPrivateKeyFile))));
      } catch (IOException e) {
        throw new RuntimeException("p2p private key file not found - " + p2pPrivateKeyFile);
      }
    } else {
      LOG.info("Private key file not supplied. A private key will be generated");
      bytes = Optional.empty();
    }
    return bytes;
  }

  public void initAttestationPool() {
    LOG.debug("BeaconChainController.initAttestationPool()");
    attestationPool = new AggregatingAttestationPool(new AttestationDataStateTransitionValidator());
    eventChannels.subscribe(SlotEventsChannel.class, attestationPool);
  }

  public void initRestAPI() {
    LOG.debug("BeaconChainController.initRestAPI()");
    DataProvider dataProvider =
        new DataProvider(
            recentChainData,
            combinedChainDataClient,
            p2pNetwork,
            syncService,
            eventChannels.getPublisher(ValidatorApiChannel.class),
            blockImporter);
    beaconRestAPI = new BeaconRestApi(dataProvider, config);
  }

  public void initBlockImporter() {
    LOG.debug("BeaconChainController.initBlockImporter()");
    blockImporter = new BlockImporter(recentChainData, forkChoice, eventBus);
  }

  public void initSyncManager() {
    LOG.debug("BeaconChainController.initSyncManager()");
    if (!config.isP2pEnabled()) {
      syncService = new NoopSyncService();
    } else {
      final PendingPool<SignedBeaconBlock> pendingBlocks = PendingPool.createForBlocks();
      final FutureItems<SignedBeaconBlock> futureBlocks =
          new FutureItems<>(SignedBeaconBlock::getSlot);
      final FetchRecentBlocksService recentBlockFetcher =
          FetchRecentBlocksService.create(p2pNetwork, pendingBlocks);
      BlockManager blockManager =
          BlockManager.create(
              eventBus,
              pendingBlocks,
              futureBlocks,
              recentBlockFetcher,
              recentChainData,
              blockImporter);
      SyncManager syncManager = SyncManager.create(p2pNetwork, recentChainData, blockImporter);
      syncService = new DefaultSyncService(blockManager, syncManager, recentChainData);
      eventChannels
          .subscribe(SlotEventsChannel.class, blockManager)
          .subscribe(FinalizedCheckpointChannel.class, pendingBlocks);
    }
  }

  private void setupInitialState() {
    StartupUtil.setupInitialState(
        recentChainData,
        config.getInteropGenesisTime(),
        config.getInitialState(),
        config.getInteropNumberOfValidators());
  }

  private void onStoreInitialized() {
    UnsignedLong genesisTime = recentChainData.getGenesisTime();
    UnsignedLong currentTime = UnsignedLong.valueOf(System.currentTimeMillis() / 1000);
    UnsignedLong currentSlot = ZERO;
    if (currentTime.compareTo(genesisTime) > 0) {
      UnsignedLong deltaTime = currentTime.minus(genesisTime);
      currentSlot = deltaTime.dividedBy(UnsignedLong.valueOf(SECONDS_PER_SLOT));
    } else {
      UnsignedLong timeUntilGenesis = genesisTime.minus(currentTime);
      LOG.info("{} seconds until genesis.", timeUntilGenesis);
    }
    nodeSlot.setValue(currentSlot);
  }

  @Override
  public void onTick(Date date) {
    if (recentChainData.isPreGenesis()) {
      return;
    }
    final UnsignedLong currentTime = UnsignedLong.valueOf(date.getTime() / 1000);
    final boolean nextSlotDue = isNextSlotDue(currentTime);

    final StoreTransaction transaction = recentChainData.startStoreTransaction();
    on_tick(transaction, currentTime);
    transaction.commit().join();

    if (nextSlotDue && syncService.isSyncActive()) {
      processSlotWhileSyncing();
      return;
    }

    final UnsignedLong genesisTime = recentChainData.getGenesisTime();
    if (currentTime.compareTo(genesisTime) < 0) {
      return;
    }
    final UnsignedLong calculatedSlot = ForkChoiceUtil.getCurrentSlot(currentTime, genesisTime);

    // tolerate 1 slot difference, not more
    if (calculatedSlot.compareTo(nodeSlot.getValue().plus(ONE)) > 0) {
      EVENT_LOG.nodeSlotsMissed(nodeSlot.getValue(), calculatedSlot);
      nodeSlot.setValue(calculatedSlot);
    }

    final UnsignedLong epoch = compute_epoch_at_slot(nodeSlot.getValue());
    final UnsignedLong nodeSlotStartTime =
        ForkChoiceUtil.getSlotStartTime(nodeSlot.getValue(), genesisTime);
    if (isSlotStartDue(calculatedSlot)) {
      processSlotStart(epoch);
    }
    if (isSlotAttestationDue(calculatedSlot, currentTime, nodeSlotStartTime)) {
      processSlotAttestation(epoch);
    }
    if (isSlotAggregationDue(calculatedSlot, currentTime, nodeSlotStartTime)) {
      processSlotAggregate();
    }
  }

  private boolean isProcessingDueForSlot(
      final UnsignedLong calculatedSlot, final UnsignedLong currentPosition) {
    return currentPosition == null || calculatedSlot.compareTo(currentPosition) > 0;
  }

  private boolean isTimeReached(final UnsignedLong currentTime, final UnsignedLong earliestTime) {
    return currentTime.compareTo(earliestTime) >= 0;
  }

  private boolean isSlotStartDue(final UnsignedLong calculatedSlot) {
    return isProcessingDueForSlot(calculatedSlot, onTickSlotStart);
  }

  // Attestations are due 1/3 of the way through the slots time period
  private boolean isSlotAttestationDue(
      final UnsignedLong calculatedSlot,
      final UnsignedLong currentTime,
      final UnsignedLong nodeSlotStartTime) {
    final UnsignedLong earliestTime = nodeSlotStartTime.plus(oneThirdSlotSeconds);
    return isProcessingDueForSlot(calculatedSlot, onTickSlotAttestation)
        && isTimeReached(currentTime, earliestTime);
  }

  // Aggregations are due 2/3 of the way through the slots time period
  private boolean isSlotAggregationDue(
      final UnsignedLong calculatedSlot,
      final UnsignedLong currentTime,
      final UnsignedLong nodeSlotStartTime) {
    final UnsignedLong earliestTime =
        nodeSlotStartTime.plus(oneThirdSlotSeconds).plus(oneThirdSlotSeconds);
    return isProcessingDueForSlot(calculatedSlot, onTickSlotAggregate)
        && isTimeReached(currentTime, earliestTime);
  }

  public boolean isNextSlotDue(final UnsignedLong currentTime) {
    final UnsignedLong nextSlotStartTime =
        recentChainData
            .getGenesisTime()
            .plus(nodeSlot.getValue().times(UnsignedLong.valueOf(SECONDS_PER_SLOT)));
    return currentTime.compareTo(nextSlotStartTime) >= 0;
  }

  private void processSlotStart(final UnsignedLong nodeEpoch) {
    onTickSlotStart = nodeSlot.getValue();
    if (nodeSlot.getValue().equals(compute_start_slot_at_epoch(nodeEpoch))) {
      EVENT_LOG.epochEvent(
          nodeEpoch,
          recentChainData.getStore().getJustifiedCheckpoint().getEpoch(),
          recentChainData.getStore().getFinalizedCheckpoint().getEpoch(),
          recentChainData.getFinalizedRoot());
    }
    slotEventsChannelPublisher.onSlot(nodeSlot.getValue());
  }

  private void processSlotAttestation(final UnsignedLong nodeEpoch) {
    onTickSlotAttestation = nodeSlot.getValue();
    Bytes32 headBlockRoot = this.forkChoice.processHead();
    EVENT_LOG.slotEvent(
        nodeSlot.getValue(),
        recentChainData.getBestSlot(),
        headBlockRoot,
        nodeEpoch,
        recentChainData.getStore().getFinalizedCheckpoint().getEpoch(),
        recentChainData.getFinalizedRoot(),
        p2pNetwork.getPeerCount());
    this.eventBus.post(new BroadcastAttestationEvent(headBlockRoot, nodeSlot.getValue()));
  }

  private void processSlotAggregate() {
    onTickSlotAggregate = nodeSlot.getValue();
    this.eventBus.post(new BroadcastAggregatesEvent(nodeSlot.getValue()));
    nodeSlot.inc();
  }

  private void processSlotWhileSyncing() {
    this.forkChoice.processHead();
    EVENT_LOG.syncEvent(
        nodeSlot.getValue(), recentChainData.getBestSlot(), p2pNetwork.getPeerCount());
    slotEventsChannelPublisher.onSlot(nodeSlot.getValue());
    nodeSlot.inc();
  }
}
