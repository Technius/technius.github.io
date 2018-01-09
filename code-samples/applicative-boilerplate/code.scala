import $ivy.`org.typelevel::cats-core:1.0.0`

import cats.implicits._
import cats.{Id, Applicative}

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

def postToDraft(post: BlogPostFull): BlogPostDraft =
  BlogPost[Option](
    title = Some(post.title),
    content = Some(post.content),
    date = Some(post.date)
  )

postToDraft(myPost) // BlogPost(Some("My post"), Some("Hello world!"), Some(2018-01-09T22:52:39.658Z))

def embed[F[_]](post: BlogPost[Id])(implicit F: cats.Applicative[F]): BlogPost[F] =
  BlogPost[F](
    title = F.pure(post.title),
    content = F.pure(post.content),
    date = F.pure(post.date)
  )

embed[Option](myPost) // BlogPost(Some("My post"), Some("Hello world!"), Some(2018-01-09T22:52:39.658Z))

type ErrorOr[A] = Either[String, A]
embed[ErrorOr](myPost) // BlogPost[ErrorOr] = BlogPost(Right(My post), Right(Hello world!), Right(2018-01-09T22:52:39.658Z))

def fullPostOpt(draft: BlogPost[Option]): Option[BlogPost[Id]] =
  (draft.title, draft.content, draft.date).mapN(BlogPost.apply[Id] _)

fullPostOpt(embed[Option](myPost)) // Some(BlogPost("My post", "Hello world!", 2018-01-09T22:52:39.658Z))
fullPostOpt(myDraft) // None

def traverse[F[_]: Applicative](draft: BlogPost[F]): F[BlogPost[Id]] =
  (draft.title, draft.content, draft.date).mapN(BlogPost.apply[Id] _)

traverse(embed[ErrorOr](myPost)) // Right(BlogPost(My post,Hello world!,2018-01-09T22:52:39.658Z))

val failedPost =
  BlogPost[ErrorOr](
    Left("Title cannot be empty"),
    Left("Content cannot be empty"),
    Right(java.time.Instant.now))

traverse(failedPost) // Left(Title cannot be empty)
