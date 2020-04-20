package org.ethereum.discv5.core

import io.libp2p.core.PeerId
import java.util.LinkedList
import java.util.Queue
import java.util.Random

interface Task {
    fun step(): Unit
    fun isOver(): Boolean {
        return false
    }
}

interface ProducerTask<R> : Task {
    fun getResult(): R
}

/**
 * Naive implementation without task updates between rounds
 */
class RecursiveTableVisit(private val node: Node, private val peerFn: (Enr) -> Unit) : Task {
    private val peers: Queue<Enr> = LinkedList()

    private fun isRoundOver(): Boolean = peers.isEmpty()

    override fun step() {
        if (isRoundOver()) {
            peers.addAll(node.table.findAll())
        }
        if (peers.isEmpty()) { // XXX: still could be empty
            return
        }
        peerFn(peers.poll())
    }
}

/**
 * Naive implementation without task updates between rounds
 */
class PingTableVisit(private val node: Node) : Task {
    private val peers: Queue<Enr> = LinkedList()

    private fun isRoundOver(): Boolean = peers.isEmpty()

    override fun step() {
        if (isRoundOver()) {
            peers.addAll(node.table.findAll())
        }
        if (peers.isEmpty()) { // XXX: still could be empty
            return
        }
        val current = peers.poll()
        node.ping(current) {
            if (!it) {
                node.table.remove(current)
            }
        }
    }
}

/**
 * Two steps message task:
 * 1) message is delivered from node to recipient
 * 2) message is handled on recipient side and result is returned back
 */
class MessageRoundTripTask(
    private val node: Node,
    private val recipient: Enr,
    private val message: Message,
    private val cb: (List<Message>) -> Unit
) : Task {
    private var deliveryDone = false
    private var isOver = false

    override fun step() {
        if (!deliveryDone) {
            deliveryDone = true
            return
        }
        val result = node.router.route(node, recipient, message)
        cb(result)
        isOver = true
    }

    override fun isOver(): Boolean {
        return isOver
    }
}

/**
 * Node update task
 */
class NodeUpdateTask(private val enr: Enr, private val node: Node) : Task {
    private var done: Boolean = false

    override fun step() {
        if (done) {
            return
        }

        node.updateNode(enr)
        this.done = true
    }

    override fun isOver(): Boolean {
        return done
    }

    override fun toString(): String {
        return "NodeUpdateTask[${enr.toId()}]"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NodeUpdateTask

        if (enr.id != other.enr.id) return false
        if (node != other.node) return false
        if (done != other.done) return false

        return true
    }

    override fun hashCode(): Int {
        var result = enr.id.hashCode()
        result = 31 * result + node.hashCode()
        result = 31 * result + done.hashCode()
        return result
    }
}

/**
 * Id search task
 * FIXME: what if we need options for a wider radius
 */
class IdSearchTask(
    private val node: Node,
    private val id: PeerId,
    private val start: Enr,
    private val alpha: Int,
    private val rnd: Random,
    private val candidateReplaceOnFail: () -> Enr
) : ProducerTask<List<Enr>> {
    private var finished = false
    private var replacement: IdSearchTask? = null
    private var firstStepOver = false
    private var firstCbPlaced = false
    private var secondCbPlaced = false
    var candidates = LinkedList<Enr>()
    private val candidateReplacement = ArrayList<List<Enr>>()

    override fun step() {
        if (finished) {
            return
        }
        if (replacement != null) {
            replacement?.step()
            return
        }

        // First step could fail
        if (!firstStepOver) {
            if (!firstCbPlaced) {
                node.findNodes(start, id, this::firstStepCb)
                firstCbPlaced = true
            }
            return
        }

        if (candidateReplacement.size == alpha) {
            this.candidates = LinkedList(KademliaTable.filterNeighborhood(id, candidateReplacement.flatten(), K_BUCKET))
            candidateReplacement.clear()
        }
        if (candidateReplacement.size < alpha && candidates.isNotEmpty()) {
            if (!secondCbPlaced) {
                node.findNodes(candidates.poll(), id, this::secondStepCb)
                secondCbPlaced = true
            }
        }
    }

    private fun firstStepCb(nodes: List<Enr>) {
        if (nodes.isEmpty()) {
            replacement = IdSearchTask(node, id, candidateReplaceOnFail(), alpha, rnd, candidateReplaceOnFail)
        } else {
            this.candidates.addAll(nodes.shuffled(rnd))
        }
        firstStepOver = true
    }

    private fun secondStepCb(nodes: List<Enr>) {
        if (nodes.isNotEmpty()) {
            candidateReplacement.add(nodes)
        }
        checkIsOver()
        secondCbPlaced = false
    }

    private fun checkIsOver() {
        if (candidateReplacement.size < alpha) {
            return
        }
        val replacement = KademliaTable.filterNeighborhood(id, candidateReplacement.flatten(), 1)[0]
        val current = KademliaTable.filterNeighborhood(id, candidates, 1)[0]
        if (replacement.id.to(id) == current.id.to(id)) {
            candidates = LinkedList(candidateReplacement.flatten())
            finished = true
        }
    }

    override fun isOver(): Boolean {
        return finished
    }

    override fun getResult(): List<Enr> {
        if (!isOver()) {
            error("Task is not over. Query result when the task is over!")
        }

        return candidates
    }

    override fun toString(): String {
        return "IdSearchTask[Node=${node.enr.toId()}, id=$id, start=${start.toId()}]"
    }
}

open class ParallelTask(private val subTasks: List<Task>) : Task {
    override fun step() {
        subTasks.forEach { it.step() }
    }

    override fun isOver(): Boolean {
        return subTasks.all { it.isOver() }
    }
}

open class ParallelQueueTask(private val subTasks: Queue<Task>, private val parallelism: Int) : Task {
    private val current = HashSet<Task>()
    override fun step() {
        while (current.size < parallelism && subTasks.isNotEmpty()) {
            current.add(subTasks.poll())
        }
        current.forEach { it.step() }
        current.removeAll { it.isOver() }
    }

    override fun isOver(): Boolean {
        return subTasks.isEmpty() && current.isEmpty()
    }
}

class ParallelProducerTask<R>(private val subTasks: List<ProducerTask<R>>) : ProducerTask<List<R>>,
    ParallelTask(subTasks) {
    override fun getResult(): List<R> {
        if (!isOver()) {
            error("Task is not over. Query result when the task is over!")
        }

        return subTasks.map { it.getResult() }.toList()
    }
}

class ParallelQueueProducerTask<R>(private val subTasks: Queue<ProducerTask<R>>, parallelism: Int) :
    ProducerTask<List<R>>,
    ParallelQueueTask(subTasks as Queue<Task>, parallelism) {
    private val tasksCopy = subTasks.toList()

    override fun getResult(): List<R> {
        if (!isOver()) {
            error("Task is not over. Query result when the task is over!")
        }

        return tasksCopy.map { it.getResult() }.toList()
    }
}

class ParallelIdSearchTask(
    private val node: Node,
    private val id: PeerId,
    private val startFn: () -> Enr,
    private val parallelism: Int,
    private val radius: Int,
    private val rnd: Random
) : ProducerTask<List<Enr>> {
    private val delegate: ParallelProducerTask<List<Enr>>

    init {
        val tasks = (0 until parallelism).map {
            startFn()
        }.map {
            IdSearchTask(node, id, it, parallelism, rnd, startFn)
        }.toList()
        this.delegate = ParallelProducerTask(tasks)
    }

    override fun step() {
        delegate.step()
    }

    override fun isOver(): Boolean {
        return delegate.isOver()
    }

    override fun getResult(): List<Enr> {
        return KademliaTable.filterNeighborhood(id, delegate.getResult().flatten(), radius)
    }
}

class ImmediateProducer(private val result: List<Enr>) : ProducerTask<List<Enr>> {
    override fun step() {
        // do nothing
    }

    override fun isOver(): Boolean {
        return true
    }

    override fun getResult(): List<Enr> {
        return result
    }
}

class AdvertiseOnMediaTask(
    private val node: Node,
    private val media: Enr,
    private val topicHash: ByteArray,
    private val adRetrySteps: Int
) : ProducerTask<Boolean> {
    private var finished = false
    private var needRetryIn: Int = 0
    private var result = false
    private var retrying = false

    override fun step() {
        if (finished) {
            return
        }
        if (needRetryIn > 0) {
            needRetryIn--
            return
        }
        placeTask()
    }

    private fun placeTask() {
        val ticket: ByteArray
        if (retrying) {
            ticket = ByteArray(TicketMessage.getAverageTicketSize())
        } else {
            ticket = ByteArray(1)
        }
        node.tasks.add(MessageRoundTripTask(node, media, RegTopicMessage(topicHash, node.enr, ticket)) {
            node.handle(
                it,
                media
            ).map { message -> message as TicketMessage }.toList().apply(this::handleAnswer)
        })
    }

    private fun handleAnswer(messages: List<TicketMessage>) {
        if (messages.isEmpty()) {
            placeTask()
            return
        }
        val message = messages[0]
        if (message.waitSteps == 0) {
            finished = true
            result = true
        } else if (message.waitSteps <= adRetrySteps) {
            needRetryIn = message.waitSteps
            retrying = true
        } else {
            finished = true
            result = false
        }
    }

    override fun isOver(): Boolean {
        return finished
    }

    override fun getResult(): Boolean {
        if (!isOver()) {
            error("Task is not over. Query result when the task is over!")
        }

        return result
    }
}

class TopicAdvertiseTask(
    private val node: Node,
    private val mediaSearchTask: ProducerTask<List<Enr>>,
    private val topicHash: ByteArray,
    private val adRetrySteps: Int,
    private val parallelism: Int,
    private val cb: (List<Boolean>) -> Unit
) : Task {
    private lateinit var advertiseTask: ProducerTask<List<Boolean>>

    override fun step() {
        if (mediaSearchTask.isOver()) {
            val tasks =
                mediaSearchTask.getResult().map { AdvertiseOnMediaTask(node, it, topicHash, adRetrySteps) }.toList()
            this.advertiseTask = ParallelQueueProducerTask<Boolean>(LinkedList(tasks), parallelism)
        } else {
            mediaSearchTask.step()
            return
        }
        if (advertiseTask.isOver()) {
            cb(advertiseTask.getResult())
            return
        }
        advertiseTask.step()
    }

    override fun isOver(): Boolean {
        return mediaSearchTask.isOver() && advertiseTask.isOver()
    }
}