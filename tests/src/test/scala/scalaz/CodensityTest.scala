package scalaz

import org.scalacheck.Arbitrary
import scalaz.scalacheck.ScalazProperties._
import scalaz.scalacheck.ScalaCheckBinding._
import std.list._
import std.option._

class CodensityTest extends SpecLite {
  implicit def arbCodensity[F[+_], A](implicit A: Arbitrary[F[A]], M: Monad[F])
      : Arbitrary[Codensity[F, A]] =
    Functor[Arbitrary].map(A)(Codensity.rep(_))

  implicit def eqlCodensity[F[+_], A](implicit A: Equal[F[A]], M: Applicative[F])
      : Equal[Codensity[F, A]] =
    Equal.equalBy(_.improve)

  checkAll("List", monadPlus.laws[({type λ[α] = Codensity[List, α]})#λ])
  checkAll("Option", monadPlus.laws[({type λ[α] = Codensity[Option, α]})#λ])

  object instances {
    def functor[F[+_]: MonadPlus] = Functor[({type λ[α] = Codensity[F, α]})#λ]
    def apply[F[+_]: MonadPlus] = Apply[({type λ[α] = Codensity[F, α]})#λ]
    def applicative[F[+_]: MonadPlus] = Applicative[({type λ[α] = Codensity[F, α]})#λ]
    def plus[F[+_]: MonadPlus] = Plus[({type λ[α] = Codensity[F, α]})#λ]
    def monad[F[+_]: MonadPlus] = Monad[({type λ[α] = Codensity[F, α]})#λ]
    def monade[F[+_]] = Monad[({type λ[α] = Codensity[F, α]})#λ]
  }
}
