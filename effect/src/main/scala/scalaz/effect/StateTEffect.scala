package scalaz
package effect

@deprecated("Instances are in the respective companion objects now and should be picked up without imports", "7.1")
object stateTEffect extends StateTEffectInstances

sealed abstract class StateTEffectInstances0 extends StateTInstances {
  @deprecated("Instances are in the respective companion objects now and should be picked up without imports", "7.1")
  implicit def StateTLiftIO[M[_], S](implicit M0: MonadIO[M]): LiftIO[({type λ[α] = StateT[M, S, α]})#λ] = new StateTLiftIO[M, S] {
    implicit def M = M0
  }
}

sealed abstract class StateTEffectInstances extends StateTEffectInstances0 {
  @deprecated("Instances are in the respective companion objects now and should be picked up without imports", "7.1")
  implicit def StateTMonadIO[M[_], S](implicit M0: MonadIO[M]): MonadIO[({type λ[α] = StateT[M, S, α]})#λ] = {
    new MonadIO[({type λ[α] = StateT[M, S, α]})#λ] with StateTLiftIO[M, S] with StateTMonadState[S, M] {
      implicit def F = M0
      implicit def M = M0
    }
  }
}

trait StateTLiftIO[M[_], S] extends LiftIO[({type λ[α] = StateT[M, S, α]})#λ] {
  implicit def M: MonadIO[M]

  def liftIO[A](ioa: IO[A]) = MonadTrans[({type λ[α[_], β] = StateT[α, S, β]})#λ].liftM(M.liftIO(ioa))
}
