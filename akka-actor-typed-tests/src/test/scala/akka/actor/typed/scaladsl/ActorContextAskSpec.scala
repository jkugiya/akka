/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, PostStop, Props, TypedAkkaSpecWithShutdown }
import akka.testkit.EventFilter
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

object ActorContextAskSpec {
  val config = ConfigFactory.parseString(
    """
      akka.loggers = ["akka.testkit.TestEventListener"]
      ping-pong-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
      snitch-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
    """)
}

class ActorContextAskSpec extends TestKit(ActorContextAskSpec.config) with TypedAkkaSpecWithShutdown {

  implicit val untyped = system.toUntyped // FIXME no typed event filter yet

  "The Scala DSL ActorContext" must {

    "provide a safe ask" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(selfName: String, threadName: String)

      val pingPong = spawn(Behaviors.immutable[Ping] { (ctx, msg) ⇒
        msg.sender ! Pong(ctx.self.path.name, Thread.currentThread().getName)
        Behaviors.same
      }, "ping-pong", Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))

      val probe = TestProbe[AnyRef]()

      val snitch = Behaviors.deferred[Pong] { (ctx) ⇒

        // Timeout comes from TypedAkkaSpec

        ctx.ask(pingPong)(Ping) {
          case Success(pong) ⇒ Pong(ctx.self.path.name + "1", Thread.currentThread().getName)
          case Failure(ex)   ⇒ throw ex
        }

        Behaviors.immutable {
          case (ctx, pong: Pong) ⇒
            probe.ref ! pong
            Behaviors.same
        }
      }

      spawn(snitch, "snitch", Props.empty.withDispatcherFromConfig("snitch-dispatcher"))

      val pong = probe.expectMsgType[Pong]

      pong.selfName should ===("snitch1")
      pong.threadName should startWith("ActorContextAskSpec-snitch-dispatcher")
    }

    "fail actor when mapping does not match response" in {
      val probe = TestProbe[AnyRef]()

      trait Protocol
      case class Ping(respondTo: ActorRef[Pong.type]) extends Protocol
      case object Pong extends Protocol

      val pingPong = spawn(Behaviors.immutable[Protocol]((_, msg) ⇒
        msg match {
          case Ping(respondTo) ⇒
            respondTo ! Pong
            Behaviors.same
        }
      ))

      val snitch = Behaviors.deferred[AnyRef] { (ctx) ⇒
        ctx.ask(pingPong)(Ping) {
          case Success(msg) ⇒ throw new NotImplementedError(msg.toString)
          case Failure(x)   ⇒ x
        }

        Behaviors.immutable[AnyRef] {
          case (_, msg) ⇒
            probe.ref ! msg
            Behaviors.same
        }.onSignal {

          case (_, PostStop) ⇒
            probe.ref ! "stopped"
            Behaviors.same
        }
      }

      EventFilter[NotImplementedError](occurrences = 1, start = "Pong").intercept {
        spawn(snitch)
      }

      // the exception should cause a failure which should stop the actor
      probe.expectMsg("stopped")
    }

    "deal with timeouts in ask" in {
      val probe = TestProbe[AnyRef]()
      val snitch = Behaviors.deferred[AnyRef] { (ctx) ⇒

        ctx.ask[String, String](system.deadLetters)(ref ⇒ "boo") {
          case Success(m) ⇒ m
          case Failure(x) ⇒ x
        }(20.millis, implicitly[ClassTag[String]])

        Behaviors.immutable {
          case (_, msg) ⇒
            probe.ref ! msg
            Behaviors.same
        }
      }

      EventFilter.warning(occurrences = 1, message = "received dead letter without sender: boo").intercept {
        EventFilter.info(occurrences = 1, start = "Message [java.lang.String] without sender").intercept {
          spawn(snitch)
        }
      }

      probe.expectMsgType[TimeoutException]

    }

  }

}
