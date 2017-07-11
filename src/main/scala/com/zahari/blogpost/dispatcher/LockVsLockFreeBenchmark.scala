package com.zahari.blogpost.dispatcher

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import org.openjdk.jmh.annotations._

import scala.annotation.tailrec

/**
  * Created by zahari on 7/3/17.
  */

object LockVsLockFreeBenchmark{

  class Counter {
    var value:Int = 0
    def increment = value = value + 1
  }

  sealed trait Rook {
    def updatePosition(offset :(Long, Long)): Unit
  }

  class LockBasedRook extends Rook {
    private var coordinates: (Long, Long)  = (0,0)
    def updatePosition(offset :(Long, Long)) = this.synchronized {
      coordinates = (coordinates._1 + offset._1, coordinates._2 + offset._2)
    }
  }

  class LockFreeRook extends Rook {
    private var coordinates = new AtomicReference[(Long, Long)]((0,0))
    @tailrec
    final def updatePosition(offset :(Long, Long)): Unit = {
      val prev = coordinates.get()
      val updated = (prev._1 + offset._1, prev._2 + offset._2)
      if (!coordinates.compareAndSet(prev, updated)) {
        Thread.`yield`()
        updatePosition(offset)
      }
    }
  }

  class ChessPlayer(r: Rook, numMoves: Int, terminationLatch: CountDownLatch) extends Runnable{
    val random = ThreadLocalRandom.current()
    def computeNextMove = {
      (random.nextLong(), random.nextLong())
    }
    override def run(): Unit = {
      for {_ <- 1 to numMoves} {
        r.updatePosition(computeNextMove)
      }
      terminationLatch.countDown()
    }
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class LockVsLockFreeBenchmark {

  import com.zahari.blogpost.dispatcher.LockVsLockFreeBenchmark._

  @Param(Array("8","32", "64"))
  var numConcurrentPlayers: Int = 0
  @Param(Array("lock-free", "lock-based"))
  var typeOfRook: String = ""
  final val numMoves = 1024


  var rook: Rook = null
  var executorService = Executors.newFixedThreadPool(8)
  var players: Seq[ChessPlayer] = null;
  var terminationLatch: CountDownLatch = null


  @Setup(Level.Invocation)
  def setup(): Unit = {
    rook = if (typeOfRook == "lock-based") new LockBasedRook else new LockFreeRook
    terminationLatch = new CountDownLatch(numConcurrentPlayers)
    players = for {_ <- 1 to numConcurrentPlayers} yield new ChessPlayer(rook,numMoves/numConcurrentPlayers,terminationLatch)
  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = {
    executorService.shutdownNow()
  }

  @Benchmark
  @OperationsPerInvocation(numMoves)
  def benchMark(): Unit = {
    players.foreach(executorService.execute)
    terminationLatch.await(1, TimeUnit.MINUTES)
  }
}
