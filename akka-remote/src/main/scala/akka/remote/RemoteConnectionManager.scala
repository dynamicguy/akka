/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.routing._
import akka.actor.ActorSystem
import akka.event.Logging

import scala.collection.immutable.Map
import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

/**
 * Remote connection manager, manages remote connections, e.g. RemoteActorRef's.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteConnectionManager(
  system: ActorSystem,
  remote: Remote,
  initialConnections: Map[RemoteAddress, ActorRef] = Map.empty[RemoteAddress, ActorRef])
  extends ConnectionManager {

  val log = Logging(system, "RemoteConnectionManager")

  // FIXME is this VersionedIterable really needed? It is not used I think. Complicates API. See 'def connections' etc.
  case class State(version: Long, connections: Map[RemoteAddress, ActorRef])
    extends VersionedIterable[ActorRef] {
    def iterable: Iterable[ActorRef] = connections.values
  }

  def failureDetector = remote.failureDetector

  private val state: AtomicReference[State] = new AtomicReference[State](newState())

  /**
   * This method is using the FailureDetector to filter out connections that are considered not available.
   */
  private def filterAvailableConnections(current: State): State = {
    val availableConnections = current.connections filter { entry ⇒ failureDetector.isAvailable(entry._1) }
    current copy (version = current.version, connections = availableConnections)
  }

  private def newState() = State(Long.MinValue, initialConnections)

  def version: Long = state.get.version

  // FIXME should not return State value but a Seq with connections
  def connections = filterAvailableConnections(state.get)

  def size: Int = connections.connections.size

  def connectionFor(address: RemoteAddress): Option[ActorRef] = connections.connections.get(address)

  def isEmpty: Boolean = connections.connections.isEmpty

  def shutdown() {
    state.get.iterable foreach (_.stop()) // shut down all remote connections
  }

  @tailrec
  final def failOver(from: RemoteAddress, to: RemoteAddress) {
    log.debug("Failing over connection from [{}] to [{}]", from, to)

    val oldState = state.get
    var changed = false

    val newMap = oldState.connections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, newConnection(to, actorRef.path))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newMap)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) {
        failOver(from, to) // recur
      }
    }
  }

  @tailrec
  final def remove(faultyConnection: ActorRef) {

    val oldState = state.get()
    var changed = false

    var faultyAddress: RemoteAddress = null
    var newConnections = Map.empty[RemoteAddress, ActorRef]

    oldState.connections.keys foreach { address ⇒
      val actorRef: ActorRef = oldState.connections.get(address).get
      if (actorRef ne faultyConnection) {
        newConnections = newConnections + ((address, actorRef))
      } else {
        faultyAddress = address
        changed = true
      }
    }

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) {
        remove(faultyConnection) // recur
      } else {
        log.debug("Removing connection [{}]", faultyAddress)
      }
    }
  }

  @tailrec
  final def putIfAbsent(address: RemoteAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef = {

    val oldState = state.get()
    val oldConnections = oldState.connections

    oldConnections.get(address) match {
      case Some(connection) ⇒ connection // we already had the connection, return it
      case None ⇒ // we need to create it
        val newConnection = newConnectionFactory()
        val newConnections = oldConnections + (address -> newConnection)

        //one or more occurrances of the actorRef were removed, so we need to update the state.
        val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) {
          // we failed, need compensating action
          newConnection.stop() // stop the new connection actor and try again
          putIfAbsent(address, newConnectionFactory) // recur
        } else {
          // we succeeded
          log.debug("Adding connection [{}]", address)
          newConnection // return new connection actor
        }
    }
  }

  private[remote] def newConnection(remoteAddress: RemoteAddress, actorPath: ActorPath) =
    RemoteActorRef(remote.system.provider, remote.server, remoteAddress, actorPath, None)
}
