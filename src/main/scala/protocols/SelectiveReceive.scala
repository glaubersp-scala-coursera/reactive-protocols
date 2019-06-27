package protocols

import akka.actor.typed.{
  Behavior,
  BehaviorInterceptor,
  Signal,
  TypedActorContext
}
import akka.actor.typed.scaladsl._

object SelectiveReceive {

  /**
    * @return A behavior that stashes incoming messages unless they are handled
    *         by the underlying `initialBehavior`
    * @param bufferSize Maximum number of messages to stash before throwing a `StashOverflowException`
    *                   Note that 0 is a valid size and means no buffering at all (ie all messages should
    *                   always be handled by the underlying behavior)
    * @param initialBehavior Behavior to decorate
    * @tparam T Type of messages
    *
    * Hint: Use [[Behaviors.intercept]] to intercept messages sent to the `initialBehavior` with
    *       the `Interceptor` defined below
    */
  def apply[T](bufferSize: Int, initialBehavior: Behavior[T]): Behavior[T] =
    Behaviors.intercept(new Interceptor[T](bufferSize))(initialBehavior)

  /**
    * An interceptor that stashes incoming messages unless they are handled by the target behavior.
    *
    * @param bufferSize Stash buffer size
    * @tparam T Type of messages
    *
    * Hint: Ue a [[StashBuffer]] and [[Behavior]] helpers such as `same`
    * and `isUnhandled`.
    */
  private class Interceptor[T](bufferSize: Int)
      extends BehaviorInterceptor[T, T] {
    import BehaviorInterceptor.{ReceiveTarget, SignalTarget}

    val stashBuffer: StashBuffer[T] = StashBuffer[T](bufferSize)

    /**
      * @param ctx Actor context
      * @param msg Incoming message
      * @param target Target (intercepted) behavior
      */
    def aroundReceive(ctx: TypedActorContext[T],
                      msg: T,
                      target: ReceiveTarget[T]): Behavior[T] = {
      val next = target(ctx, msg)
      // If the `next` behavior has not handled the incoming `msg`, stash the `msg` and
      // return an unchanged behavior. Otherwise, return a behavior resulting from
      // “unstash-ing” all the stashed messages to the `next` behavior.
      if (Behavior.isUnhandled(next)) {
        if (stashBuffer.isFull) {
          throw new StashOverflowException(
            s"Stash is full and could not stash message $msg"
          )
        } else {
          if (bufferSize == 0) {
            Behavior.same
          } else {
            stashBuffer.stash(msg)
            Behavior.unhandled
          }
        }
      } else {
        stashBuffer.unstashAll(ctx.asScala, SelectiveReceive(bufferSize, next))
      }
    }

    // Forward signals to the target behavior
    def aroundSignal(ctx: TypedActorContext[T],
                     signal: Signal,
                     target: SignalTarget[T]): Behavior[T] =
      target(ctx, signal)

  }

}
