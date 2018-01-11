import $ivy.`com.chuusai::shapeless:2.3.3`
import $ivy.`org.typelevel::kittens:1.0.0-RC2`
import shapeless.{ HList, Generic, Lazy }
import shapeless.poly.~>
import shapeless.ops.hlist.NatTRel
import cats.implicits._
import cats.{ Id, Applicative }
import cats.sequence.Sequencer

def traverse[A[_[_]], F[_], IdRepr <: HList, FRepr <: HList](af: A[F])(
  implicit F: Applicative[F],
  genId: Generic.Aux[A[Id], IdRepr],
  genF: Generic.Aux[A[F], FRepr],
  seq: Lazy[Sequencer.Aux[FRepr, F, IdRepr]]
): F[A[Id]] = {
  F.map(seq.value.apply(genF.to(af)))(genId.from _)
}

class Rank2Traverse[F[_]] {
  def apply[A[_[_]], IdRepr <: HList, FRepr <: HList](af: A[F])(
    implicit
    F: Applicative[F],
    genId: Generic.Aux[A[Id], IdRepr],
    genF: Generic.Aux[A[F], FRepr],
    seq: Lazy[Sequencer.Aux[FRepr, F, IdRepr]]
  ): F[A[Id]] =
  F.map(seq.value.apply(genF.to(af)))(genId.from _)
}

def traverse[F[_]]: Rank2Traverse[F] = new Rank2Traverse[F]

traverse[Option](Foo[Option](Some(1), Some("asdf"))) // Some(Foo(1, "asdf"))
traverse[Option](Foo[Option](Some(1), None)) // None
