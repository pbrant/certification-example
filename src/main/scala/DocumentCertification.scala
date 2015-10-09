import scala.util.Random
import scalaz.{Nondeterminism, \/}
import scalaz.concurrent.Task
import scalaz.std.option._
import scalaz.syntax.std.option._
import scalaz.stream._
import scalaz.syntax.monad._
import scalaz.syntax.std.boolean._

import java.util.concurrent.atomic._

import scala.concurrent.duration._

import scala.language.higherKinds

object DocumentCertification {
  trait UncertifiedDocument
  object UncertifiedDocument extends UncertifiedDocument

  trait CertifiedDocument
  object CertifiedDocument extends CertifiedDocument

  val nextDocumentCounter: AtomicInteger = new AtomicInteger(0)
  val docCounter: AtomicInteger = new AtomicInteger(0)

  val running: AtomicBoolean = new AtomicBoolean(true)

  implicit val timeoutScheduler = scalaz.concurrent.Strategy.DefaultTimeoutScheduler

  def takeWhileTrue[F[_], A](b: AtomicBoolean, p: Process[F, A]): Process[F, A] =
    p.takeWhile(_ => b.get())

  def log(s: String): Unit = println(s"[${new java.util.Date().toString()}] $s")

  def certify(doc: UncertifiedDocument): Throwable \/ CertifiedDocument = {
    \/.fromTryCatchNonFatal {
      if (Random.nextInt(3) == 0)
        throw new Exception("Document certification failed")
      else {
        Thread.sleep(1000L)
        log("Successfully certified document")
        CertifiedDocument
      }
    }
  }

  def nextDocument(): Option[UncertifiedDocument] = {
    Thread.sleep(500L)
    (nextDocumentCounter.incrementAndGet() % 4 != 0).option{
      log("Returning uncertified document #" + docCounter.incrementAndGet())
      UncertifiedDocument
    }
  }

  def saveSuccess(doc: CertifiedDocument): Unit = {
    if (Random.nextInt(5) == 0) {
      throw new Exception("Could not save document")
    }
    log("Saving certified document")
  }

  def saveError(t: Throwable): Unit = {
    log("Document certification failed with message: " + t.getMessage())
  }

  def save(doc: Throwable \/ CertifiedDocument): Unit = {
    doc.swap.foreach(saveError)
    doc.foreach(saveSuccess)
  }

  def wait(seconds: Int): Process[Task, Unit] =
    takeWhileTrue(running, time.awakeEvery(1.seconds).take(seconds)).void

  val outstanding: Process0[UncertifiedDocument] =
    Process.unfold(())(_ => nextDocument() ∘ ((_, ())))

  val certifyChannel: Channel[Task, UncertifiedDocument, Throwable \/ CertifiedDocument] =
    channel.lift((doc: UncertifiedDocument) => Task.delay(certify(doc)))

  val saveSink: Sink[Task, Throwable \/ CertifiedDocument] =
    sink.lift((doc: Throwable \/ CertifiedDocument) => Task.delay(save(doc)))

  val certifyOutstanding: Process[Task, Unit] =
    takeWhileTrue(running, outstanding.toSource) through certifyChannel to saveSink

  val certifyContinuously: Process[Task, Unit] =
    (certifyOutstanding ++ wait(10)).repeat.onFailure { t =>
      log("Certification process died with message: " + t.getMessage());
      log("Waiting five seconds before continuing")
      wait(5) ++ certifyContinuously
    }

  def run: Task[Unit] = {
    val msg = Task.delay(log("Running certification process for 53 seconds"))
    val interrupt = Task.schedule(running.set(false), 53.seconds)

    Nondeterminism[Task].gather(List(msg >> interrupt, Task.fork(certifyContinuously.run))).void
  }
}
