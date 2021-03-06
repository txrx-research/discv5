package org.ethereum.discv5

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import org.ethereum.discv5.core.Enr
import org.ethereum.discv5.core.Node
import org.ethereum.discv5.core.Router
import org.ethereum.discv5.util.ByteArrayWrapper
import org.ethereum.discv5.util.InsecureRandom
import org.ethereum.discv5.util.KeyUtils
import org.ethereum.discv5.util.RoundCounter
import org.ethereum.discv5.util.calcKademliaPeers
import org.ethereum.discv5.util.calcPeerDistribution
import org.ethereum.discv5.util.calcTraffic
import org.ethereum.discv5.util.formatTable

val PEER_COUNT = 10000
val RANDOM: InsecureRandom = InsecureRandom().apply { setInsecureSeed(1) }
val REGISTER_ROUNDS = 300
val SEARCH_ROUNDS = 300
val SUBNET_SHARE_PCT = 5 // Which part of all peers are validators from one tracked subnet, in %
val TARGET_PERCENTILE = 95
val SUBNET_13 = ByteArrayWrapper(ByteArray(1) { 13.toByte() })
val REQUIRE_ADS = 8
val SEACHERS_COUNT = 10

fun main(args: Array<String>) {
    println("Creating private key - enr pairs for $PEER_COUNT nodes")
    val router = Router(RANDOM)
    val peers = (0 until (PEER_COUNT)).map {
        val ip = "127.0.0.1"
        val privKey = KeyUtils.genPrivKey(RANDOM)
        val addr = Multiaddr("/ip4/$ip/tcp/$it")
        val enr = Enr(
            addr,
            PeerId(KeyUtils.privToPubCompressed(privKey))
        )
        Node(enr, privKey, RANDOM, router).apply {
            router.register(this)
        }
    }

    // It's believed that p2p node uptime is complied with Pareto distribution
    // See Stefan Saroiu, Krishna P. Gummadi, Steven D. Gribble "A Measurement Study of Peer-to-Peer File Sharing Systems"
    // https://www.researchgate.net/publication/2854843_A_Measurement_Study_of_Peer-to-Peer_File_Sharing_Systems
    // For simulations lets assume that it's Pareto with alpha = 0.18 and xm = 1.
    // With such distribution 40% of peers are mature enough to pass through its Kademlia table all peers in network.
    val alpha = 0.18
    val xm = 1.0
    println("""Calculating distribution of peer tables fullness with alpha = $alpha and Xm = $xm""")
    val peerDistribution = calcPeerDistribution(
        PEER_COUNT,
        alpha,
        xm,
        240.0
    )
    assert(peers.size == peerDistribution.size)
    println("Filling peer's Kademlia tables according to distribution")
    peers.forEachIndexed() { index, peer ->
        (1..peerDistribution[index]).map { peers[RANDOM.nextInt(PEER_COUNT)] }
            .forEach { peer.table.put(it.enr) { enr, cb -> cb(true) } }
        if (index > 0 && index % 1000 == 0) {
            println("$index peer tables filled")
        }
    }

    // TODO: Uncomment when need to see only initial fill of Kademlia tables
//    printKademliaTableStats(peers)
//    System.exit(-1)

    // TODO: Uncomment to set churn rate
//    router.churnPcts = 25

    // TODO: Uncomment when need simulation with placement of topic advertisement
//    println("Run simulation with placing topic ads Discovery V5 protocol")
//    val topicSimulator = TopicSimulator()
//    topicSimulator.runTopicAdSimulationUntilSuccessfulPlacement(peers, RoundCounter(REGISTER_ROUNDS), router)
//    peers.forEach(Node::resetAll)
//    topicSimulator.runTopicSearch(peers, RoundCounter(SEARCH_ROUNDS), SEACHERS_COUNT, REQUIRE_ADS)

    // TODO: Uncomment when need simulation with using ENR attribute for advertisement
    println("Run simulation with ENR attribute advertisement")
    val enrSimulator = EnrSimulator()
    val enrStats = enrSimulator.runEnrUpdateSimulationUntilDistributed(peers, RoundCounter(REGISTER_ROUNDS))
//    enrSimulator.visualizeSubnetPeersStats(peers)
    enrSimulator.printSubnetPeersStats(enrStats)
    peers.forEach(Node::resetAll)
    enrSimulator.runEnrSubnetSearch(peers, RoundCounter(SEARCH_ROUNDS), SEACHERS_COUNT, REQUIRE_ADS)
}

fun gatherTrafficStats(peers: List<Node>): List<Long> {
    return peers.map { calcTraffic(it) }.sorted()
}

fun printKademliaTableStats(peers: List<Node>) {
    println("Kademlia table stats for every peer")
    println("===================================")
    val header = "Peer #\t Stored nodes\n"
    val stats = peers.mapIndexed { index, peer ->
        index.toString() + "\t" + calcKademliaPeers(
            peer.table
        )
    }.joinToString("\n")
    println((header + stats).formatTable(true))
}
