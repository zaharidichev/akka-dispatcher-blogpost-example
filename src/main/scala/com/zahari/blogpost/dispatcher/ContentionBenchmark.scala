package com.zahari.blogpost.dispatcher

import java.util.concurrent._

import akka.dispatch.AbstractBoundedNodeQueue
import com.zahari.blogpost.dispatcher.ContentionBenchmark.{WorkItem, WorkProducer}
import org.openjdk.jmh.annotations._

import scala.util.Random

/**
  * Created by zahari on 7/5/17.
  */
class BoundedTaskQueue(capacity: Int) extends AbstractBoundedNodeQueue[WorkItem](capacity)

object ContentionBenchmark {
  class WorkItem(value: String)
  class WorkProducer(numItemsToProduce:Int,
                     queue: BoundedTaskQueue,
                     terminationLatch:CountDownLatch) extends Runnable {
    val r = new Random()
    override def run(): Unit = {
      for {_ <- 1 to numItemsToProduce} {
        queue.add(new WorkItem(r.nextString(5)))
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
class ContentionBenchmark {

  import com.zahari.blogpost.dispatcher.ContentionBenchmark._

  final val executorService =  Executors.newFixedThreadPool(2)
  final val numWorkItems = 128000
  @Param(Array("32"))
  var numWorkProducers = 0

  @Param(Array("striped", "non-striped"))
  var mode = ""

  var workProducers: Seq[WorkProducer] = null
  var terminationLatch: CountDownLatch = null

  @Setup(Level.Invocation)
  def setup() = {
    terminationLatch = new CountDownLatch(numWorkProducers)
    val numItemsPerProducer = numWorkItems/numWorkProducers
    if (mode == "striped") {
      workProducers = Seq.fill(numWorkProducers)(new WorkProducer(numItemsPerProducer,new BoundedTaskQueue(numItemsPerProducer),terminationLatch))
    } else {
      val q = new BoundedTaskQueue(numWorkItems)
      workProducers = Seq.fill(numWorkProducers)(new WorkProducer(numItemsPerProducer,q,terminationLatch))
    }
  }

  @TearDown(Level.Trial)
  def tearDown(): Unit = {
    executorService.shutdownNow()
  }

  @Benchmark
  @OperationsPerInvocation(numWorkItems)
  def benchmark = {
    workProducers.foreach(executorService.execute)
    terminationLatch.await(1,TimeUnit.MINUTES)
  }
}
