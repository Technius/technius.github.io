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

In fact, we can turn a full blog post into any `BlogPost[F]` if it's possible to
create a `F[T]` from a `T`. In other words, if `F` is an `Applicative`, then we
can define

```scala
def embed[F[_]](post: BlogPost[Id])(implicit F: cats.Applicative[F]): BlogPost[F] =
  BlogPost[F](
    title = F.pure(post.title),
    content = F.pure(post.content),
    date = F.pure(post.date)
  )
```

Since `Option` is an `Applicative`, `embed` can turn a full post into a draft,
as expected. It also works on a partially applied `Either`, since `Either` is an
also applicative:

```scala
import cats.implicits._
embed[Option](myPost) // BlogPost(Some("My post"), Some("Hello world!"), Some(2018-01-09T22:52:39.658Z))

type ErrorOr[A] = Either[String, A]
embed[ErrorOr](myPost) // BlogPost[ErrorOr] = BlogPost(Right(My post), Right(Hello world!), Right(2018-01-09T22:52:39.658Z))
```

But how about turning a draft into a full post? It would work if all of the
fields are defined, but not if one or more fields aren't defined.

```scala
def fullPostOpt(draft: BlogPost[Option]): Option[BlogPost[Id]] =
  (draft.title, draft.content, draft.date).mapN(BlogPost.apply[Id] _)

fullPostOpt(embed[Option](myPost)) // Some(BlogPost("My post", "Hello world!", 2018-01-09T22:52:39.658Z))
fullPostOpt(myDraft) // None
```

Here, I've used the [`Applicative` syntax][applicative-syntax] for `Option`,
which can be used to map multiple applicative values into a single applicative
value. Since I've only used a function on `Applicative`, but nothing
about `Option` itself, it seems like a version of `fullPostOpt` can be
implemented for any applicative.

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
To have all errors collected simultaneously, use `Validated` (which has an
`Applicative` instance) instead.

## Deriving a type-generic version of traverse

TODO

## Conclusion

[applicative-syntax]: https://typelevel.org/cats/typeclasses/applicative.html
[functor-functors]: https://www.benjamin.pizza/posts/2017-12-15-functor-functors.html
