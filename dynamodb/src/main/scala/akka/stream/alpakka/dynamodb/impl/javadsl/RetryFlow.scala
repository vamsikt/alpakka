/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.dynamodb.impl.javadsl

import java.util

import akka.NotUsed
import akka.japi.Pair
import akka.stream.alpakka.dynamodb.impl.scaladsl
import akka.stream.javadsl.{Flow, FlowWithContext, Keep}

import scala.concurrent.duration._
import scala.util.Try
import scala.collection.JavaConverters._

object RetryFlow {

  /**
   * Allows retrying individual elements in the stream with exponential backoff.
   *
   * The retry condition is controlled by the `retryWith` partial function. It takes an output element of the wrapped
   * flow and should return one or more requests to be retried.
   *
   * A successful or failed response will be propagated downstream if it is not matched by the `retryFlow` function.
   *
   * If a successful response is matched and issued a retry, the response is still propagated downstream.
   *
   * The implementation of the RetryFlow assumes that `flow` follows one-in-out-out element semantics.
   *
   * The wrapped `flow` and `retryWith` takes an additional `State` parameter which can be used to correlate a request
   * with a response.
   *
   * Backoff state is tracked separately per-element coming into the wrapped `flow`.
   *
   * @param parallelism controls the number of in-flight requests in the wrapped flow
   * @param minBackoff minimum duration to backoff between issuing retries
   * @param maxBackoff maximum duration to backoff between issuing retries
   * @param randomFactor adds jitter to the retry delay. Use 0 for no jitter
   * @param flow a flow to retry elements from
   * @param retryWith retry condition decision partial function
   */
  def withBackoff[In, Out, State, Mat](
      parallelism: Int,
      minBackoff: java.time.Duration,
      maxBackoff: java.time.Duration,
      randomFactor: Double,
      flow: Flow[Pair[In, State], Pair[Try[Out], State], Mat],
      retryWith: java.util.function.Function[Pair[Try[Out], State], akka.japi.Option[util.Collection[Pair[In, State]]]]
  ): Flow[akka.japi.Pair[In, State], akka.japi.Pair[Try[Out], State], Mat] = {
    val retryFlow = scaladsl.RetryFlow
      .withBackoffAndContext(parallelism,
                             Duration.fromNanos(minBackoff.toNanos),
                             Duration.fromNanos(maxBackoff.toNanos),
                             randomFactor,
                             FlowWithContext.fromPairs(flow).asScala) {
        case (t, s) =>
          retryWith(Pair.create(t, s))
            .map(coll => coll.asScala.toIndexedSeq.map(pair => (pair.first, pair.second)))
      }
      .asFlow

    Flow
      .create[akka.japi.Pair[In, State]]()
      .map(func(p => (p.first, p.second)))
      .viaMat(retryFlow, Keep.right[NotUsed, Mat])
      .map(func(t => akka.japi.Pair.create(t._1, t._2)))
  }

  private def func[T, R](f: T => R) = new akka.japi.function.Function[T, R] {
    override def apply(param: T): R = f(param)
  }

}
