+++
date = "2017-04-30T11:23:06-07:00"
title = "Cleaner Error Handling in Scala with Cats EitherT"
tags = ["scala"]
categories = ["Scala"]
draft = true
+++

# Introduction

In Scala, it's a common practice to handle errors or perform validation using
`Option` or `Either`. For example, a form on a website may be validated on a
server by using a series of `Either`s, which will return the valid model data or
a message explaining the problems in the form submission. However, adding
more complicated concerns into the `Either` sequence, such as `Option`s or
`Future`s, requires a lot of boilerplate.

To see how this might happen, let's consider a simple example, account
registration. In our case, an account consists of a username, a password and an
email address.

```scala
case class Account(username: String, password: String, email: String)
```

To register for an account, a new user has to provide all three of these fields,
which usually follow a set of restrictions. For example, most websites require
passwords to have a minimum length, and some websites don't allow usernames to
have spaces. The user registration form might be validated like this:


```scala
// Note: This example requires Scala 2.12, which allows Either to be used
// in for comprehensions

import scala.util.{ Either, Left, Right }

type ErrorOr[A] = Either[String, A]

def validateUsername(username: String): ErrorOr[String] = ???
def validatePassword(password: String): ErrorOr[String] = ???
def validateEmail(email: String): ErrorOr[String] = ???

def validateAccount(usernameInput: String, passwordInput: String, emailInput: String): ErrorOr[Account] =
  for {
    username <- validateUsername(usernameInput)
    password <- validatePassword(passwordInput)
    email <- validateEmail(emailInput)
  } yield Account(usernameInput, passwordInput, email)
```

This looks clean, but creating the actual account isn't. For example, user
registration might involve checking for existing accounts, saving the account a
database, and sending a welcome email. In addition, the aforementioned actions
may need to occur asynchronously, which means that we have to handle `Future`s,
as well.

```scala
def findAccountWithEmail(email: String): Future[Option[Account]] = ???
def sendWelcomeEmail(email: String): Future[Unit] = ???
def insertAccountIntoDatabase(newAccount: Account): Future[Account] = ???

def registerAccount(usernameInput: String, passwordInput: String, emailInput: String): Future[ErrorOr[Account]] = {
  validateAccount(usernameInput, passwordInput, emailInput).fold(
    error => Future.successful(Left(error)),
    validAccount => findAccountWithEmail(emailInput) flatMap {
      case Some(_) =>
        val errorMessage = "Account with this email already exists!"
        Future.successful(Left(errorMessage))
      case None =>
        for {
          _ <- insertAccountIntoDatabase(validAccount)
          _ <- sendWelcomeEmail(validAccount.email)
        } yield Right(validAccount)
    }
  )
}
```

There's a lot of noise that distracts from how the process works. Also, it's a
pain to write the boilerplate, especially since form validation is quite common.

The problem is that the steps don't compose very well. It would be far nicer if
we could just write a single for-comprehension that deals strictly with `Either`
and let some other underlying mechanism handle the `Future` boilerplate. For
example, a clean hypothetical example of a `registerAccount` function would be

```scala
def registerAccount(usernameInput: String, passwordInput: String, emailInput: String): Future[ErrorOr[Account]] =
  for {
    validAccount <- validateAccount(usernameInput, passwordInput, emailInput)
    accountOpt <- findAccount(validAccount.email) if accountOpt.isEmpty
    _ <- insertAccountIntoDatabase(validAccount)
    _ <- sendWelcomeEmail(validAccount.email)
  } yield validAccount
```

# Abstracting Away Either Handling

We can get pretty close to that hypothetical example by using the `EitherT`
monad transformer. It's not necessary to know what a "monad transformer" is,
only that `EitherT` is a wrapper for some effectful type (e.g. `Option` or
`Future`) that can abstract away the effect and handle the contents of the type
in a more convenient manner. I'm going to use the `EitherT` from cats, but the
`EitherT` from scalaz should also work.

Using `EitherT` is pretty straightforward: wrap your desired data in `EitherT`,
compose the `EitherT` values using a for-comprehension, and then extract the
final wrapped `F[Either[B, A]]` using the `value` method, where `F` is the
effectful type, `B` is the error type, and `A` is the type of the valid data.
Here's an example:

```scala
type Result[A] = EitherT[Future, String, A] // wraps a Future[Either[String, A]]

val numberET: Result[Int] = EitherT.pure(5) // pure has type A  => EitherT[F, B, A]
val numberOpt = Some(10)

val finalEitherT = for {
  n <- numberET

  // fromOption transforms an Option into an Right if it exists, or a Left with
  // erroraneous value otherwise.
  numberOpt <- EitherT.fromOption(numberOpt, "Number not defined")
} yield (n + numberOpt)

val myFuture: Future[Either[String, Int]] = finalEitherT.value
```

TODO failures

# Implementing `registerAccount`

TODO

```scala
def testForExistingAccount(email: String): Result[Unit] =
    EitherT(findAccountWithEmail(email) map {
      case Some(_) => Left("Account with this email already exists")
      case None => Right(())
    })

def registerAccount(usernameInput: String, passwordInput: String, emailInput: String): Future[ErrorOr[Account]] = {
  val eitherT: Result[Account] = for {
    newAccount <- validateAccount(usernameInput, passwordInput, emailInput)
    _ <- testForExistingAccount(emailInput)
    _ <- EitherT(insertAccountIntoDatabase(newAccount))
    _ <- EitherT(sendWelcomeEmail(newAccount.email))
  } yield newAccount

  eitherT.value
}
```

# Conclusion

I've shown that using `EitherT` can make data validation far more readable.
However, my examples can still be improved in various ways:

* Use an algebraic data type to model error instead of using strings. ADTs have
  better type safety, can be exhaustively checked by the compiler in pattern
  matches, and are easily serialized using one of the many available JSON
  libraries.
* Due to its short-ciruiting nature, `Either` might not be desirable in cases
  such as form validation, where multiple fields could be incorrect at the same
  time. The alternative is the `Validated` (or `Validation`) type, which can be
  used to perform validations in parallel. Luckily, a `Validated` can also be
  used inside of an `EitherT` with a little bit of effort. For the best effect,
  use _both_ `Either` and `Validated`, the former for sequential validation, and
  the latter for parallel validation. See the [`Either`][either_docs_cats]
  and [`Validation`][validated_docs_cats] documentation for more information.

[fs2_task]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/0.9.5/fs2-core_2.12-0.9.5-javadoc.jar/!/fs2/Task.html
[fs2]: https://github.com/functional-streams-for-scala/fs2
[cats]: http://typelevelorg/cats
[either_docs]: http://scala-lang.org/files/archive/api/current/scala/util/Either.html
[either_docs_cats]: http://typelevel.org/cats/datatypes/either.html
[validated_docs_cats]: http://typelevel.org/cats/datatypes/validated.html
