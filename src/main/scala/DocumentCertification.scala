import java.util.concurrent.atomic._

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.Random

import scalaz.concurrent.Task
import scalaz.{Nondeterminism, \/}
import scalaz.std.option._
import scalaz.stream._
import scalaz.syntax.monad._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._

trait UncertifiedDocument
object UncertifiedDocument extends UncertifiedDocument

trait CertifiedDocument
object CertifiedDocument extends CertifiedDocument

object Util {
  private implicit val timeoutScheduler = scalaz.concurrent.Strategy.DefaultTimeoutScheduler

  def log(s: String): Unit = println(s"[${new java.util.Date().toString()}] $s")

  private def continueWhileTrue(bool: AtomicBoolean): Process[Task, Unit] =
    Process.repeatEval(Task.delay(bool.get())).takeWhile(identity).void

  def pause(bool: AtomicBoolean, seconds: Int): Process[Task, Unit] =
    (continueWhileTrue(bool) >> {
      log("Pausing one second")
      time.awakeEvery(1.seconds).once.void
    }).take(seconds)

  def feedIncrementally[A](running: AtomicBoolean, op: => Option[A]): Process[Task, A] =
    Process.unfoldEval(())(_ => Task.delay(
      running.get.option(op).join ∘ ((_, ()))
    ))

  def repeatWhileRunning[A](running: AtomicBoolean, p: => Process[Task, A]): Process[Task, A] =
    p ++ (if (running.get()) repeatWhileRunning(running, p) else Process.halt)
}

object CertificationService {
  import Util._

  val nextDocumentCounter: AtomicInteger = new AtomicInteger(0)
  val docCounter: AtomicInteger = new AtomicInteger(0)

  def nextDocument(): Option[UncertifiedDocument] = {
    log("Looking for next document")
    (nextDocumentCounter.incrementAndGet() % 4 != 0).option{
      log("Returning uncertified document #" + docCounter.incrementAndGet())
      UncertifiedDocument
    }
  }

  def saveCertifiedDocument(doc: CertifiedDocument): Unit = {
    if (Random.nextInt(4) == 0) {
      throw new Exception("Could not save document")
    }
    log("Saving certified document")
  }

  def saveError(doc: UncertifiedDocument, t: Throwable): Unit = {
    log("Document certification failed with message: " + t.getMessage())
  }

  def certify(doc: UncertifiedDocument): Throwable \/ CertifiedDocument = {
    \/.fromTryCatchNonFatal {
      if (Random.nextInt(3) == 0)
        throw new Exception("Document certification failed")
      else {
        log("Successfully certified document")
        CertifiedDocument
      }
    }
  }

  def save(uncertified: UncertifiedDocument, doc: Throwable \/ CertifiedDocument): Unit = {
    doc.swap.foreach(t => CertificationService.saveError(uncertified, t))
    doc.foreach(CertificationService.saveCertifiedDocument)
  }
}

object DocumentCertification {
  import Util._

  val running: AtomicBoolean = new AtomicBoolean(false)

  val outstanding: Process[Task, UncertifiedDocument] =
    feedIncrementally(running, CertificationService.nextDocument())

  val certifyChannel: Channel[
      Task,
      UncertifiedDocument,
      (UncertifiedDocument, Throwable \/ CertifiedDocument)
  ] =
    channel.lift((doc: UncertifiedDocument) =>
      Task.delay((doc, CertificationService.certify(doc)))
    )

  val saveSink: Sink[
    Task,
    (UncertifiedDocument, Throwable \/ CertifiedDocument)
  ] =
    sink.lift((pair: (UncertifiedDocument, Throwable \/ CertifiedDocument)) =>
      Task.delay(CertificationService.save(pair._1, pair._2))
    )

  val certifyOutstanding: Process[Task, Unit] =
    outstanding through certifyChannel to saveSink

  val certifyAndPause: Process[Task, Unit] = certifyOutstanding ++ pause(running, 10)

  def certifyContinuously: Process[Task, Unit] =
    repeatWhileRunning(running, certifyAndPause).onFailure { t =>
      log("Certification process died with message: " + t.getMessage());
      log("Waiting five seconds before continuing")
      pause(running, 5) ++ certifyContinuously
    }

  def run: Task[Unit] = {
    if (! running.get()) {
      running.set(true)
      val msg = Task.delay(log("Running certification process for 53 seconds"))
      val interrupt = Task.schedule(running.set(false), 53.seconds)

      Nondeterminism[Task].gather(List(msg >> interrupt, Task.fork(certifyContinuously.run))).void
    } else {
      Task.now(())
    }
  }
}
