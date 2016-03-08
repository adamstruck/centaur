package centaur

import java.util.UUID

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.{HttpRequest, FormData}
import cats.Monad

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import spray.httpx.SprayJsonSupport._
import CromwellStatusJsonSupport._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * A simplified riff on the final tagless pattern where the interpreter (monad & related bits) are fixed. Operation
  * functions create an instance of a Test and override the run method to do their bidding. It is unlikely that you
  * should be modifying Test directly, instead most likely what you're looking to do is add a function to the Operations
  * object below
  */
sealed abstract class Test[A] {
  def run: Try[A]
}

object Test {
  implicit val testMonad: Monad[Test] = new Monad[Test] {
    override def flatMap[A, B](fa: Test[A])(f: A => Test[B]): Test[B] = {
      new Test[B] {
        override def run: Try[B] = fa.run flatMap { f(_).run }
      }
    }

    override def pure[A](x: A): Test[A] = {
      new Test[A] {
        override def run: Try[A] = Try(x)
      }
    }
  }
}

/**
  * Defines functions which are building blocks for test formulas. Each building block is expected to perform
  * a single task and these tasks can be composed together to form arbitrarily complex test strategies. For instance
  * submitting a workflow, polling until a status is reached, retrieving metadata, verifying some value, delaying for
  * N seconds - these would all be operations.
  *
  * All operations are expected to return a Test type and implement the run method. These can then
  * be composed together via a for comprehension as a test formula and then run by some other entity.
  */
object Operations {
  def submitWorkflow(request: WorkflowRequest): Test[Workflow] = {
    new Test[Workflow] {
      override def run: Try[Workflow] = {
        val formData = FormData(List("wdlSource" -> request.wdl, "workflowInputs" -> request.inputs, "workflowOptions" -> request.options))
        val response = Pipeline(Post(CentaurConfig.cromwellUrl + "/api/workflows/v1", formData))
        sendReceiveFutureCompletion(response map { _.id } map UUID.fromString map { Workflow(_, CentaurConfig.cromwellUrl) })
      }
    }
  }

  def pollUntilStatus(workflow: Workflow, expectedStatus: WorkflowStatus): Test[Workflow] = {
    new Test[Workflow] {
      @tailrec
      def doPerform(): Workflow = {
        val response = Pipeline(Get(CentaurConfig.cromwellUrl + "/api/workflows/v1/" + workflow.id + "/status"))
        val status = sendReceiveFutureCompletion(response map { r => WorkflowStatus(r.status) })
        status match {
          case Success(s) if s == expectedStatus => workflow
          case Failure(f) => throw f
          case _ =>
            Thread.sleep(10000) // This could be a lot smarter including cromwell style backoff
            doPerform()
        }
      }

      override def run: Try[Workflow] = workflowLengthFutureCompletion(Future { doPerform() } )
    }
  }

  /**
    * Ensure that the Future completes within the specified timeout. If it does not, or if the Future fails,
    * will return a Failure, otherwise a Success
    */
  def awaitFutureCompletion[T](x: Future[T], timeout: FiniteDuration) = Try(Await.result(x, timeout))
  def sendReceiveFutureCompletion[T](x: Future[T]) = awaitFutureCompletion(x, CentaurConfig.sendReceiveTimeout)
  def workflowLengthFutureCompletion[T](x: Future[T]) = awaitFutureCompletion(x, CentaurConfig.maxWorkflowLength)

  // Spray needs an implicit ActorSystem
  implicit val system = ActorSystem("centaur-foo")
  // FIXME: Pretty sure this will be insufficient once we move past submit & polling, but hey, continuous improvement!
  val Pipeline: HttpRequest => Future[CromwellStatus] = sendReceive ~> unmarshal[CromwellStatus]
}


