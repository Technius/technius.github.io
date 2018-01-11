+++
date = "2018-01-09T13:49:37-08:00"
lastmod = "2018-01-09"
title = "Reducing Boilerplate in Similar Types with Applicative and Shapeless"
tags = ["scala"]
categories = ["Scala"]
draft = true
+++

[Functor Functors][functor-functors] presents a way to reduce code duplication
in similar data structures using an applicative type parameter. One idea of the
article is that if you have some types that share the same fields, but with a
different higher-kinded type wrapping those fields, then the types can be
generalized by using the higher-kinded type as a type parameter.

TODO should have more explanations

For example, let's say we have a blog post and a draft version of a blog post.

```scala
case class BlogPostFull(
  title: String,
  content: String,
  date: java.time.Instant
)

case class BlogPostDraft(
  title: Option[String],
  content: Option[String],
  date: Option[java.time.Instant]
)
```

## Generalizing blog posts

Note that the type of each field, say `T`, in `BlogPostFull` is equivalent to
`Id[T]`, the identity functor acting on `T`. That means that `BlogPostFull` and
`BlogPostDraft` only differ on the `F` wrapping each field (e.g. `Id[String]`
and `Option[String]`). We can generalize the idea of a blog post by using a type
parameter `F[_]`:

```scala
case class BlogPost[F[_]](
  title: F[String],
  content: F[String],
  date: F[java.time.Instant]
)

 // Using cats in this blog post, but scalaz works just as well
type BlogPostFull = BlogPost[cats.Id]
type BlogPostDraft = BlogPost[Option]

val myPost: BlogPostFull = BlogPost[Id]("My post", "Hello world!", java.time.Instant.now)
val myDraft: BlogPostDraft = BlogPost[Option](Some("My draft"), None, None)
```

If we have a full blog post, then we can easily turn it into a draft by putting
each field into a `Some`.

```scala
def postToDraft(post: BlogPostFull): BlogPostDraft =
  BlogPost[Option](
    title = Some(post.title),
    content = Some(post.content),
    date = Some(post.date)
  )

postToDraft(myPost) // BlogPost(Some("My post"), Some("Hello world!"), Some(2018-01-09T22:52:39.658Z))
```

In fact, if we can find a way to turn a `T` into a `F[T]`, that is, a function
`T => F[T]`, we can turn any `BlogPost[F]` into a full blog post. That's exactly
what the `pure` function on an [applicative functor][applicative] does! In other
words, if `F` is an `Applicative`, then we can define

```scala
def embed[F[_]](post: BlogPost[Id])(implicit F: cats.Applicative[F]): BlogPost[F] =
  BlogPost[F](
    title = F.pure(post.title),
    content = F.pure(post.content),
    date = F.pure(post.date)
  )
```

Since `Option` has an `Applicative` instance, `embed` can turn a full post into
a draft, as expected. It also works on a partially applied `Either`, since
`Either` is an also an applicative functor:

```scala
import cats.implicits._
embed[Option](myPost) // BlogPost(Some("My post"), Some("Hello world!"), Some(2018-01-09T22:52:39.658Z))

type ErrorOr[A] = Either[String, A]
embed[ErrorOr](myPost) // BlogPost[ErrorOr] = BlogPost(Right(My post), Right(Hello world!), Right(2018-01-09T22:52:39.658Z))
```

But how about turning a draft into a full post? Multiple applicative values can
be combined and mapped over simultaneously, which means that we can simply take
all of the fields of the draft blog post and map them to the full blog post.

```scala
def fullPostOpt(draft: BlogPost[Option]): Option[BlogPost[Id]] =
  (draft.title, draft.content, draft.date).mapN(BlogPost.apply[Id] _)

fullPostOpt(embed[Option](myPost)) // Some(BlogPost("My post", "Hello world!", 2018-01-09T22:52:39.658Z))
fullPostOpt(myDraft) // None
```

Here, I've used the [`Applicative` syntax][applicative-syntax] for `Option`,
which provides a convenient way to perform the mapping. Since I've only used a
function on `Applicative`, but didn't use any functions involving `Option`s
themselves, it seems like a version of `fullPostOpt` can be implemented for any
applicative functor.

```scala
def traverse[F[_]: Applicative](draft: BlogPost[F]): F[BlogPost[Id]] =
  (draft.title, draft.content, draft.date).mapN(BlogPost.apply[Id] _)

traverse(embed[ErrorOr](myPost)) // Right(BlogPost(My post,Hello world!,2018-01-09T22:52:39.658Z))

val failedPost =
  BlogPost[ErrorOr](
    Left("Title cannot be empty"),
    Left("Content cannot be empty"),
    Right(java.time.Instant.now))

traverse(failedPost) // Left(Title cannot be empty)
```

Note here that since `Either` is sequential, only the first error is reported.
To have all errors collected simultaneously, use `Validated` (which also has an
`Applicative` instance) instead.

The way that `BlogPost` can be traversed is quite nice, but there's a
disadvantage: we have to define `traverse` for every single type that we'd want
to use `traverse` with. It would be nice if `traverse` worked with any arbitrary
type:

```scala
def traverse[A[_[_]], F[_]: Applicative](af: A[F]): F[A[Id]]

case class MyConfig[F[_]](someValue: F[Boolean], anotherValue: F[Int])
traverse[Option](MyConfig(Some(true), None)) // should be None
traverse[Option](MyConfig(Some(false), Some(1))) // should be Some(MyConfig[Id](false, 1))
```

## Deriving a fully generic version of traverse

TODO

```scala
def traverse[A[_[_]], F[_], IdRepr <: HList, FRepr <: HList](af: A[F])(
  implicit F: Applicative[F],
  genId: Generic.Aux[A[Id], IdRepr],
  genF: Generic.Aux[A[F], FRepr],
  seq: Lazy[Sequencer.Aux[FRepr, F, IdRepr]]
): F[A[Id]] = {
  F.map(seq.value.apply(genF.to(af)))(genId.from _)
}
```

```scala
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
```


## Conclusion

TODO

[applicative]: https://typelevel.org/cats/typeclasses/applicative.html
[functor-functors]: https://www.benjamin.pizza/posts/2017-12-15-functor-functors.html
