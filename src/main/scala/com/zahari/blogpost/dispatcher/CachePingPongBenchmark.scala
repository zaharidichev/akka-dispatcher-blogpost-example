package com.zahari.blogpost.dispatcher

import java.util.concurrent._
import org.openjdk.jmh.annotations._

/**
  * Created by zahari on 7/3/17.
  */
object CachePingPongBenchmark {

  class Rook {
    @volatile private var xPos: Long = 0l
    @volatile private var yPos: Long = 0l
    def updatePosition(offset: (Long, Long)): Unit = {
      xPos += offset._1
      yPos += offset._2
    }
  }

  class ChessPlayer(moves: Int,
                    rooks: Stream[Rook],
                    terminationLatch: CountDownLatch)
    extends Runnable {

    val lRand = ThreadLocalRandom.current()
    override def run(): Unit = {
      rooks.take(moves).foreach(_.updatePosition((lRand.nextInt, lRand.nextInt)))
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
class CachePingPongBenchmark {

  import com.zahari.blogpost.dispatcher.CachePingPongBenchmark._


  var terminationLatch: CountDownLatch = null
  var chessPlayers: Seq[ChessPlayer] = null
  @Param(Array("sharedRooks", "separateRooks"))
  var mode: String = ""

  final val numMovesPerPlayer =  50000
  final val numTotalOperations = numMovesPerPlayer * 2
  final val executorService =  Executors.newFixedThreadPool(2)

  @Setup(Level.Invocation)
  def setup(): Unit = {
    terminationLatch = new CountDownLatch(2)
    val rook1 = new Rook()
    val rook2 = new Rook()
    def sharedRooks: Stream[Rook] = Stream(rook1, rook2) append sharedRooks
    def player(rooks: Stream[Rook]) =
      new ChessPlayer(numMovesPerPlayer,sharedRooks,terminationLatch)

    chessPlayers = mode match {
      case "sharedRooks" => Seq(player(sharedRooks),player(sharedRooks))
      case  "separateRooks" =>
        Seq(player(Stream.continually(rook1)),player(Stream.continually(rook2)))
    }

  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = {
    executorService.shutdownNow()
  }

  @Benchmark
  @OperationsPerInvocation(numTotalOperations)
  def benchmark = {
    chessPlayers.foreach(executorService.execute)
    terminationLatch.await(1, TimeUnit.MINUTES)
  }

}
