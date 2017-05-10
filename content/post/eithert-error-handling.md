+++
date = "2017-05-09T22:04:06-07:00"
title = "Cleaner Error Handling in Scala with Cats EitherT"
tags = ["scala"]
categories = ["Scala"]
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
`EitherT` from scalaz should also work (albeit with different function names).

Using `EitherT` is pretty straightforward: wrap your desired data in `EitherT`,
compose the `EitherT` values using a for-comprehension, and then extract the
final wrapped `F[Either[B, A]]` using the `value` method, where `F` is the
effectful type, `B` is the error type, and `A` is the type of the valid data.
Here's an example using `Future`:

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

val myFuture: Future[Either[String, Int]] = finalEitherT.value // convert EitherT to Future

val lifted: Result[Int] = EitherT.fromEither(Right(5)) // convert Either to EitherT
```

Failures work as expected, conforming to the short-circuiting nature of `Either`:

```scala
val successful: Result[Int] = EitherT.pure(5)
val fail: Result[Int] = EitherT.fromEither(Left("Nope"))
val neverReached: Result[Int] = EitherT.pure(5)

val myEitherT: Result[Int] = for {
  a <- successful
  b <- fail
  c <- neverReached
} yield c

println(myEitherT.value) // Nope
```

Try out the `EitherT` functions with different values and combinations, and see
what you get!

There's also a convenient function called `cond`, which is similar to an
if-statement for `EitherT`.

```scala
def asyncDivide(n: Int, divisor: Int): Result[Int] =
  EitherT.cond(divisor != 0, n / divisor, "Cannot divide by zero")

asyncDivide(5, 0) // Cannot divide by zero
asyncDivide(10, 2) // Successful
```

# Reimplementing `registerAccount`

Now that we're armed with `EitherT`, let's reimplement `registerAccount` in a
more elegant way. The goal is to make the logic more explicit by ordering each
step sequentially. First, let's bring back the handy `Result` alias:

```scala
type Result[A] = EitherT[Future, String, A]
```

Next, let's refactor the `validateAccount` logic. Since `Either` is already
returned for each step, all we have to do is lift each `Either` with
`EitherT.fromEither`.

```scala
def validateAccount(usernameInput: String, passwordInput: String, emailInput: String): Result[Account] =
  for {
    username <- EitherT.fromEither(validateUsername(usernameInput))
    password <- EitherT.fromEither(validatePassword(passwordInput))
    email <- EitherT.fromEither(validateEmail(emailInput))
  } yield Account(usernameInput, passwordInput, email)
```

The problematic part is testing for the existing account, since it causes the
logic to branch off:

```scala
def findAccountWithEmail(email: String): Future[Option[Account]] = ???
```

If the account can't be found, it should return an error message and stop the
registration immediately. Otherwise, it should continue with registration.

```scala
def testForExistingAccount(email: String): Result[Unit] =
    EitherT(findAccountWithEmail(email) map {
      case Some(_) => Left("An account with this email already exists")
      case None => Right(())
    })
```

Now, all that remains is to compose the steps in the `registerAccount` function.
This should be trivial, since the types that we're dealing with are `Future`,
`Either`, and `EitherT`, which can all be combined into `EitherT` in a single
for-comprehension.

```scala
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

This is pretty close to the ideal code, and it's very easy to understand!

# Conclusion

I've shown that using `EitherT` can make error handling far more readable. Like
I briefly mentioned above, `EitherT` works for effectful types such as
`Option[Either[String, Int]]` or `IO[Either[String, Int]]`. Since these types
are so generally, it's easy to see that `EitherT` has a large variety of use
cases, especially for short-circuiting steps.

Sometimes, it might be desirable to use `EitherT` in situations involving
parallel validation (e.g. validate all fields at the same time and return a list
of all errors). In that case, with some effort, `Validated` (or `Validation`)
can be used with `EitherT` to add parallel validation. Use `Either` for
sequential validation and `Validated` for parallel validation for the best
effects! See the [`Either`][either_docs_cats]
and [`Validation`][validated_docs_cats] documentation for more information.

[cats]: http://typelevelorg/cats
[either_docs]: http://scala-lang.org/files/archive/api/current/scala/util/Either.html
[either_docs_cats]: http://typelevel.org/cats/datatypes/either.html
[validated_docs_cats]: http://typelevel.org/cats/datatypes/validated.html
