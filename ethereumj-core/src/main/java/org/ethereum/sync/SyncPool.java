package org.ethereum.sync;

import org.ethereum.config.SystemProperties;
import org.ethereum.core.Blockchain;
import org.ethereum.listener.EthereumListener;
import org.ethereum.net.rlpx.Node;
import org.ethereum.net.rlpx.discover.NodeHandler;
import org.ethereum.net.rlpx.discover.NodeManager;
import org.ethereum.net.server.Channel;
import org.ethereum.net.server.ChannelManager;
import org.ethereum.util.Functional;
import org.ethereum.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.min;
import static org.ethereum.util.BIUtil.isIn20PercentRange;

/**
 * <p>Encapsulates logic which manages peers involved in blockchain sync</p>
 *
 * Holds connections, bans, disconnects and other peers logic<br>
 * The pool is completely threadsafe<br>
 * Implements {@link Iterable} and can be used in "foreach" loop<br>
 * Used by {@link SyncManager}
 *
 * @author Mikhail Kalinin
 * @since 10.08.2015
 */
@Component
public class SyncPool {

    public static final Logger logger = LoggerFactory.getLogger("sync");

    private static final long WORKER_TIMEOUT = 3; // 3 seconds

    private final List<Channel> activePeers = Collections.synchronizedList(new ArrayList<Channel>());

    private BigInteger lowerUsefulDifficulty = BigInteger.ZERO;

    @Autowired
    private EthereumListener ethereumListener;

    @Autowired
    private NodeManager nodeManager;

    private ChannelManager channelManager;

    private Blockchain blockchain;

    private SystemProperties config;

    private ScheduledExecutorService poolLoopExecutor = Executors.newSingleThreadScheduledExecutor();

    private Functional.Predicate<NodeHandler> nodesSelector;
    private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public SyncPool(final SystemProperties config, final Blockchain blockchain) {
        this.config = config;
        this.blockchain = blockchain;
    }

    public void init(final ChannelManager channelManager) {
        if (this.channelManager != null) return; // inited already
        this.channelManager = channelManager;
        updateLowerUsefulDifficulty();

        poolLoopExecutor.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            heartBeat();
                            updateLowerUsefulDifficulty();
                            fillUp();
                            prepareActive();
                            cleanupActive();
                        } catch (Throwable t) {
                            logger.error("Unhandled exception", t);
                        }
                    }
                }, WORKER_TIMEOUT, WORKER_TIMEOUT, TimeUnit.SECONDS
        );
        logExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    logActivePeers();
                    logger.info("\n");
                } catch (Throwable t) {
                    t.printStackTrace();
                    logger.error("Exception in log worker", t);
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void setNodesSelector(Functional.Predicate<NodeHandler> nodesSelector) {
        this.nodesSelector = nodesSelector;
    }

    public void close() {
        try {
            poolLoopExecutor.shutdownNow();
        } catch (Exception e) {
            logger.warn("Problems shutting down executor", e);
        }
    }

    @Nullable
    public synchronized Channel getAnyIdle() {
        ArrayList<Channel> channels = new ArrayList<>(activePeers);
        Collections.shuffle(channels);
        for (Channel peer : channels) {
            if (peer.isIdle())
                return peer;
        }

        return null;
    }

    @Nullable
    public Channel getAnyIdleAndLock(SyncState syncState) {
        Channel peer = getAnyIdle();
        if (peer != null) {
            boolean success = peer.getEthHandler().setStatus(syncState);
            if (success) return peer;
        }
        return null;
    }

    @Nullable
    public synchronized Channel getBestIdle() {
        for (Channel peer : activePeers) {
            if (peer.isIdle())
                return peer;
        }
        return null;
    }

    @Nullable
    public synchronized Channel getMediocreIdle() {
        List<Channel> worstHalf = activePeers.subList((int) Math.floor(activePeers.size() / 2), activePeers.size());
        if (!worstHalf.isEmpty()) {
            Collections.shuffle(worstHalf);
            return worstHalf.get(0);
        }
        return null;
    }

    public synchronized List<Channel> getAllIdle() {
        List<Channel> ret = new ArrayList<>();
        for (Channel peer : activePeers) {
            if (peer.isIdle())
                ret.add(peer);
        }
        return ret;
    }

    public synchronized List<Channel> getActivePeers() {
        return new ArrayList<>(activePeers);
    }

    @Nullable
    public synchronized Channel getByNodeId(byte[] nodeId) {
        return channelManager.getActivePeer(nodeId);
    }

    public synchronized void onDisconnect(Channel peer) {
        if (activePeers.remove(peer)) {
            logger.info("Peer {}: disconnected", peer.getPeerIdShort());
        }
    }

    public synchronized Set<String> nodesInUse() {
        Set<String> ids = new HashSet<>();
        for (Channel peer : channelManager.getActivePeers()) {
            ids.add(peer.getPeerId());
        }
        return ids;
    }

    synchronized void logActivePeers() {
        if (logger.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("Peer stats:\n");
            sb.append("Active peers\n");
            sb.append("============\n");
            Set<Node> activeSet = new HashSet<>();
            for (Channel peer : new ArrayList<>(activePeers)) {
                sb.append(peer.logSyncStats()).append('\n');
                activeSet.add(peer.getNode());
            }
            sb.append("Other connected peers\n");
            sb.append("============\n");
            for (Channel peer : new ArrayList<>(channelManager.getActivePeers())) {
                if (!activeSet.contains(peer.getNode())) {
                    sb.append(peer.logSyncStats()).append('\n');
                }
            }
            logger.info(sb.toString());
        }
    }

    private void fillUp() {
        int lackSize = config.maxActivePeers() - channelManager.getActivePeers().size();
        if(lackSize <= 0) return;

        final Set<String> nodesInUse = nodesInUse();
        nodesInUse.add(Hex.toHexString(config.nodeId()));   // exclude home node

        class NodeSelector implements Functional.Predicate<NodeHandler> {
            BigInteger lowerDifficulty;

            public NodeSelector(BigInteger lowerDifficulty) {
                this.lowerDifficulty = lowerDifficulty;
            }

            @Override
            public boolean test(NodeHandler handler) {
                if (nodesInUse.contains(handler.getNode().getHexId())) {
                    return false;
                }

                if (handler.getNodeStatistics().isPredefined()) return true;

                if (nodesSelector != null && !nodesSelector.test(handler)) return false;

                if (lowerDifficulty.compareTo(BigInteger.ZERO) > 0 && handler.getNodeStatistics().getEthTotalDifficulty() == null) {
                    return false;
                }

                if (handler.getNodeStatistics().getReputation() < 100) return false;

                return handler.getNodeStatistics().getEthTotalDifficulty().compareTo(lowerDifficulty) >= 0;
            }
        }

        List<NodeHandler> newNodes;
        newNodes = nodeManager.getNodes(new NodeSelector(lowerUsefulDifficulty), lackSize);
        if (lackSize > 0 && newNodes.isEmpty()) {
            newNodes = nodeManager.getNodes(new NodeSelector(BigInteger.ZERO), lackSize);
        }

        if (logger.isTraceEnabled()) {
            logDiscoveredNodes(newNodes);
        }

        for(NodeHandler n : newNodes) {
            channelManager.connect(n.getNode());
        }
    }

    private synchronized void prepareActive() {
        List<Channel> active = new ArrayList<>(channelManager.getActivePeers());

        if (active.isEmpty()) return;

        // filtering by 20% from top difficulty
        Collections.sort(active, new Comparator<Channel>() {
            @Override
            public int compare(Channel c1, Channel c2) {
                return c2.getTotalDifficulty().compareTo(c1.getTotalDifficulty());
            }
        });

        BigInteger highestDifficulty = active.get(0).getTotalDifficulty();
        int thresholdIdx = min(config.syncPeerCount(), active.size()) - 1;

        for (int i = thresholdIdx; i >= 0; i--) {
            if (isIn20PercentRange(active.get(i).getTotalDifficulty(), highestDifficulty)) {
                thresholdIdx = i;
                break;
            }
        }

        List<Channel> filtered = active.subList(0, thresholdIdx + 1);

        // sorting by latency in asc order
        Collections.sort(filtered, new Comparator<Channel>() {
            @Override
            public int compare(Channel c1, Channel c2) {
                return Double.valueOf(c1.getPeerStats().getAvgLatency()).compareTo(c2.getPeerStats().getAvgLatency());
            }
        });

        for (Channel channel : filtered) {
            if (!activePeers.contains(channel)) {
                ethereumListener.onPeerAddedToSyncPool(channel);
            }
        }

        activePeers.clear();
        activePeers.addAll(filtered);
    }

    private synchronized void cleanupActive() {
        Iterator<Channel> iterator = activePeers.iterator();
        while (iterator.hasNext()) {
            Channel next = iterator.next();
            if (next.isDisconnected()) {
                logger.info("Removing peer " + next + " from active due to disconnect.");
                iterator.remove();
            }
        }
    }


    private void logDiscoveredNodes(List<NodeHandler> nodes) {
        StringBuilder sb = new StringBuilder();
        for(NodeHandler n : nodes) {
            sb.append(Utils.getNodeIdShort(Hex.toHexString(n.getNode().getId())));
            sb.append(", ");
        }
        if(sb.length() > 0) {
            sb.delete(sb.length() - 2, sb.length());
        }
        logger.trace(
                "Node list obtained from discovery: {}",
                nodes.size() > 0 ? sb.toString() : "empty"
        );
    }

    private void updateLowerUsefulDifficulty() {
        BigInteger td = blockchain.getTotalDifficulty();
        if (td.compareTo(lowerUsefulDifficulty) > 0) {
            lowerUsefulDifficulty = td;
        }
    }


    private void heartBeat() {
//        for (Channel peer : channelManager.getActivePeers()) {
//            if (!peer.isIdle() && peer.getSyncStats().secondsSinceLastUpdate() > config.peerChannelReadTimeout()) {
//                logger.info("Peer {}: no response after {} seconds", peer.getPeerIdShort(), config.peerChannelReadTimeout());
//                peer.dropConnection();
//            }
//        }
    }
}
