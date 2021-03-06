/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReachabilitySpec extends WordSpec with Matchers {

  import Reachability.{ Reachable, Unreachable, Terminated, Record }

  val nodeA = UniqueAddress(Address("akka.tcp", "sys", "a", 2552), 1)
  val nodeB = UniqueAddress(Address("akka.tcp", "sys", "b", 2552), 2)
  val nodeC = UniqueAddress(Address("akka.tcp", "sys", "c", 2552), 3)
  val nodeD = UniqueAddress(Address("akka.tcp", "sys", "d", 2552), 4)
  val nodeE = UniqueAddress(Address("akka.tcp", "sys", "e", 2552), 5)

  "Reachability table" must {

    "be reachable when empty" in {
      val r = Reachability.empty
      r.isReachable(nodeA) should be(true)
      r.allUnreachable should be(Set.empty)
    }

    "be unreachable when one observed unreachable" in {
      val r = Reachability.empty.unreachable(nodeB, nodeA)
      r.isReachable(nodeA) should be(false)
      r.allUnreachable should be(Set(nodeA))
    }

    "not be reachable when terminated" in {
      val r = Reachability.empty.terminated(nodeB, nodeA)
      r.isReachable(nodeA) should be(false)
      // allUnreachable doesn't include terminated
      r.allUnreachable should be(Set.empty)
      r.allUnreachableOrTerminated should be(Set(nodeA))
    }

    "not change terminated entry" in {
      val r = Reachability.empty.terminated(nodeB, nodeA)
      r.reachable(nodeB, nodeA) should be theSameInstanceAs (r)
      r.unreachable(nodeB, nodeA) should be theSameInstanceAs (r)
    }

    "not change when same status" in {
      val r = Reachability.empty.unreachable(nodeB, nodeA)
      r.unreachable(nodeB, nodeA) should be theSameInstanceAs (r)
    }

    "be unreachable when some observed unreachable and others reachable" in {
      val r = Reachability.empty.unreachable(nodeB, nodeA).unreachable(nodeC, nodeA).reachable(nodeD, nodeA)
      r.isReachable(nodeA) should be(false)
    }

    "be reachable when all observed reachable again" in {
      val r = Reachability.empty.unreachable(nodeB, nodeA).unreachable(nodeC, nodeA).
        reachable(nodeB, nodeA).reachable(nodeC, nodeA).
        unreachable(nodeB, nodeC).unreachable(nodeC, nodeB)
      r.isReachable(nodeA) should be(true)
    }

    "be pruned when all records of an observer are Reachable" in {
      val r = Reachability.empty.
        unreachable(nodeB, nodeA).unreachable(nodeB, nodeC).
        unreachable(nodeD, nodeC).
        reachable(nodeB, nodeA).reachable(nodeB, nodeC)
      r.isReachable(nodeA) should be(true)
      r.isReachable(nodeC) should be(false)
      r.records should be(Vector(Record(nodeD, nodeC, Unreachable, 1L)))

      val r2 = r.unreachable(nodeB, nodeD).unreachable(nodeB, nodeE)
      r2.records.toSet should be(Set(
        Record(nodeD, nodeC, Unreachable, 1L),
        Record(nodeB, nodeD, Unreachable, 5L),
        Record(nodeB, nodeE, Unreachable, 6L)))
    }

    "have correct aggregated status" in {
      val records = Vector(
        Reachability.Record(nodeA, nodeB, Reachable, 2),
        Reachability.Record(nodeC, nodeB, Unreachable, 2),
        Reachability.Record(nodeA, nodeD, Unreachable, 3),
        Reachability.Record(nodeD, nodeB, Terminated, 4))
      val versions = Map(nodeA -> 3L, nodeC -> 3L, nodeD -> 4L)
      val r = Reachability(records, versions)
      r.status(nodeA) should be(Reachable)
      r.status(nodeB) should be(Terminated)
      r.status(nodeD) should be(Unreachable)
    }

    "have correct status for a mix of nodes" in {
      val r = Reachability.empty.
        unreachable(nodeB, nodeA).unreachable(nodeC, nodeA).unreachable(nodeD, nodeA).
        unreachable(nodeC, nodeB).reachable(nodeC, nodeB).unreachable(nodeD, nodeB).
        unreachable(nodeD, nodeC).reachable(nodeD, nodeC).
        reachable(nodeE, nodeD).
        unreachable(nodeA, nodeE).terminated(nodeB, nodeE)

      r.status(nodeB, nodeA) should be(Unreachable)
      r.status(nodeC, nodeA) should be(Unreachable)
      r.status(nodeD, nodeA) should be(Unreachable)

      r.status(nodeC, nodeB) should be(Reachable)
      r.status(nodeD, nodeB) should be(Unreachable)

      r.status(nodeA, nodeE) should be(Unreachable)
      r.status(nodeB, nodeE) should be(Terminated)

      r.isReachable(nodeA) should be(false)
      r.isReachable(nodeB) should be(false)
      r.isReachable(nodeC) should be(true)
      r.isReachable(nodeD) should be(true)
      r.isReachable(nodeE) should be(false)

      r.allUnreachable should be(Set(nodeA, nodeB))
      r.allUnreachableFrom(nodeA) should be(Set(nodeE))
      r.allUnreachableFrom(nodeB) should be(Set(nodeA))
      r.allUnreachableFrom(nodeC) should be(Set(nodeA))
      r.allUnreachableFrom(nodeD) should be(Set(nodeA, nodeB))

      r.observersGroupedByUnreachable should be(Map(
        nodeA -> Set(nodeB, nodeC, nodeD),
        nodeB -> Set(nodeD),
        nodeE -> Set(nodeA)))
    }

    "merge by picking latest version of each record" in {
      val r1 = Reachability.empty.unreachable(nodeB, nodeA).unreachable(nodeC, nodeD)
      val r2 = r1.reachable(nodeB, nodeA).unreachable(nodeD, nodeE).unreachable(nodeC, nodeA)
      val merged = r1.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r2)

      merged.status(nodeB, nodeA) should be(Reachable)
      merged.status(nodeC, nodeA) should be(Unreachable)
      merged.status(nodeC, nodeD) should be(Unreachable)
      merged.status(nodeD, nodeE) should be(Unreachable)
      merged.status(nodeE, nodeA) should be(Reachable)

      merged.isReachable(nodeA) should be(false)
      merged.isReachable(nodeD) should be(false)
      merged.isReachable(nodeE) should be(false)

      val merged2 = r2.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r1)
      merged2.records.toSet should be(merged.records.toSet)
    }

    "merge by taking allowed set into account" in {
      val r1 = Reachability.empty.unreachable(nodeB, nodeA).unreachable(nodeC, nodeD)
      val r2 = r1.reachable(nodeB, nodeA).unreachable(nodeD, nodeE).unreachable(nodeC, nodeA)
      // nodeD not in allowed set
      val allowed = Set(nodeA, nodeB, nodeC, nodeE)
      val merged = r1.merge(allowed, r2)

      merged.status(nodeB, nodeA) should be(Reachable)
      merged.status(nodeC, nodeA) should be(Unreachable)
      merged.status(nodeC, nodeD) should be(Reachable)
      merged.status(nodeD, nodeE) should be(Reachable)
      merged.status(nodeE, nodeA) should be(Reachable)

      merged.isReachable(nodeA) should be(false)
      merged.isReachable(nodeD) should be(true)
      merged.isReachable(nodeE) should be(true)

      merged.versions.keySet should be(Set(nodeB, nodeC))

      val merged2 = r2.merge(allowed, r1)
      merged2.records.toSet should be(merged.records.toSet)
      merged2.versions should be(merged.versions)
    }

    "merge correctly after pruning" in {
      val r1 = Reachability.empty.unreachable(nodeB, nodeA).unreachable(nodeC, nodeD)
      val r2 = r1.unreachable(nodeA, nodeE)
      val r3 = r1.reachable(nodeB, nodeA) // nodeB pruned
      val merged = r2.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r3)

      merged.records.toSet should be(Set(
        Record(nodeA, nodeE, Unreachable, 1),
        Record(nodeC, nodeD, Unreachable, 1)))

      val merged3 = r3.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r2)
      merged3.records.toSet should be(merged.records.toSet)
    }

    "merge versions correctly" in {
      val r1 = Reachability(Vector.empty, Map(nodeA -> 3L, nodeB -> 5L, nodeC -> 7L))
      val r2 = Reachability(Vector.empty, Map(nodeA -> 6L, nodeB -> 2L, nodeD -> 1L))
      val merged = r1.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r2)

      val expected = Map(nodeA -> 6L, nodeB -> 5L, nodeC -> 7L, nodeD -> 1L)
      merged.versions should be(expected)

      val merged2 = r2.merge(Set(nodeA, nodeB, nodeC, nodeD, nodeE), r1)
      merged2.versions should be(expected)
    }

    "remove node" in {
      val r = Reachability.empty.
        unreachable(nodeB, nodeA).
        unreachable(nodeC, nodeD).
        unreachable(nodeB, nodeC).
        unreachable(nodeB, nodeE).
        remove(Set(nodeA, nodeB))

      r.status(nodeB, nodeA) should be(Reachable)
      r.status(nodeC, nodeD) should be(Unreachable)
      r.status(nodeB, nodeC) should be(Reachable)
      r.status(nodeB, nodeE) should be(Reachable)
    }

  }
}
