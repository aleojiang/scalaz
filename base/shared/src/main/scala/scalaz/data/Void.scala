package scalaz
package data

import com.github.ghik.silencer.silent

trait VoidModule {
  type Void

  def absurd[A](v: Void): A

  def unsafeVoid: Void

  def isNothing: Void === Nothing

  def conforms[A]: Void <~< A
}

trait VoidFunctions {
  @inline final def absurd[A](v: Void): A = Void.absurd[A](v)
}

trait VoidSyntax {
  implicit class Ops(v: Void) {
    def absurd[A]: A = Void.absurd(v)
  }
}

// NOTE[alex]: this is some next level black compiler magic
// but without this object syntax doesn't resolve...
object VoidSyntax extends VoidSyntax

@silent
private[data] object VoidImpl extends VoidModule with VoidSyntax {
  type Void = Nothing

  def absurd[A](v: Void): A = v

  private[data] final class UnsafeVoid extends RuntimeException

  def unsafeVoid: Void = throw new UnsafeVoid

  def isNothing: Void === Nothing = Is.refl[Void]

  def conforms[A]: Void <~< A = As.refl[Void]
}
