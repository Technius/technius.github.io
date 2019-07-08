+++
title = "Improving Akka HTTP REST API Response Handling with Marshallers"
date = "2017-03-24T23:54:00-07:00"
tags = ["scala", "akka-http", "rest-api", "web"]
categories = ["Scala"]
+++

I've been trying out [akka-http][akka_http] for a while now, and I found it to
be great for making REST APIs. However, it is complicated to use and can lead to
a massive amount of boilerplate if its features aren't taken advantage of
completely. In this article, I'm going to to take a look at one feature of Akka
HTTP that I explored recently: marshallers.

## Motivation

To demonstrate how status codes are usually returned in Akka HTTP, let's
consider a simple example of a REST API: an online notepad. Each note should
have a title and content. The API should have basic CRUD endpoints:

* `GET /notes` retrieves all notes.
* `POST /notes?title=Foo&content=bar` creates a new note titled "", storing
  "bar" as its content. The status code is `201 Created`.
* `GET /notes/123` retrieves the note with id 123, if it exists.
* `POST /notes/123?title=Foo2&content=bar2` updates the note with id 123, if
  it exists. If neither title nor content are supplied, return
  `304 Not Modified`.
* `DELETE /notes/123` deletes the note with id 123, if it exists.

Let's begin by modeling each note:

```scala
case class Note(id: Int, title: String, content: String)
```

Then, let's define an interface which will handle how the `Notes` are stored:

```scala
trait NoteRepository {
  def list(): Future[Seq[Note]]
  def find(id: Int): Future[Option[Note]]
  def create(title: String, content: String): Future[Note]
  def update(id: Int, title: Option[String], content: Option[String]): Future[Option[Note]]
  def delete(id: Int): Future[Option[Note]]
}
```

Before we move on to writing the routes, let's write an intermediate layer that
will help translate the results into a standard, easy-to-serialize format. In
most cases, that format would be an algebraic data type:

```scala
/* Represents the response to an API call */
sealed trait ApiResult
object ApiResult {
  /* Returns notes found */
  case class FoundNote(note: Note) extends ApiResult
  
  /* Indicates when a specified note cannot be found */
  case class NoteNotFound(id: Int) extends ApiResult
  
  /* Indicates when a note is successfully created */
  case class CreatedNote(note: Note) extends ApiResult
 
  /* Indicates when a note is successfully updated */
  case class UpdatedNote(updatedNote: Note) extends ApiResult
 
  /* Indicates when a note is sucessfully deleted. */
  case class DeletedNote(deletedNote: Note) extends ApiResult
}

trait NoteService {
  import ApiResult._

  def repo: NoteRepository

  def find(id: Int): Future[ApiResult] =
    repo.find(id).map(noteOp(id)(FoundNote.apply _))

  def create(title: String, content: String): Future[ApiResult] =
    repo.create(title, content).map(CreatedNote.apply _)

  def update(id: Int, title: Option[String], content: Option[String]): Future[ApiResult] =
    repo.update(id, title, content).map(noteOp(id)(UpdatedNote.apply _))

  def delete(id: Int): Future[ApiResult] =
    repo.delete(id).map(noteOp(id)(DeletedNote.apply _))

  /*
   * Helper for returning NoteNotFound if an Option is None, or passing the
   * found note to f otherwise.
   */
  private def noteOp[T](id: Int)(f: Note => ApiResult): Option[Note] => ApiResult =
    opt => opt.map(f).getOrElse(NoteNotFound(id))
}
```

Finally, we can define our routes. The most straightforward approach is to call
a serialization function wherever `ApiResult` is returned. For simplicity, I
used the excellent [upickle](http://www.lihaoyi.com/upickle-pprint/upickle/)
library to serialize each `ApiResult` as JSON.

```scala
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import upickle.default._
trait Routes {
  def noteService: NoteService
  
  val notesRoute =
    pathPrefix("notes") {
      // -> /notes/id
      (path(IntNumber)) { id =>
        get {
          complete {
            noteService.find(id).map(write(_))
          }
        } ~
        delete {
          complete {
            noteService.delete(id).map(write(_))
          }
        } ~
        (post & parameters('title.?, 'content.?)) { (title, content) =>
          complete {
            noteService.update(id, title, content).map(write(_))
          }
        }
      } ~
      // -> /notes
      pathEndOrSingleSlash {
        get {
          complete {
            noteService.list.map(write(_))
          }
        } ~
        (post & parameters('title, 'content)) { (title, content) =>
          complete {
            noteService.create(title, content).map(write(_))
          }
        }
      }
    }
}
```

To try it out, you'll need an implementation of `NoteRepository`. I've written a
complete, naive example in this [gist][impl_gist] (Warning: do _not_ use this
code in production!). If we run a few queries on the server, we'll get the
expected results:

```shell
$ curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[]}

$ curl 'http://localhost:1234/notes?title=test&content=Hi' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":1,"title":"test","content":"Hi"}}

$ curl 'http://localhost:1234/notes/1' -w '\n\n'
{"$type":"example.ApiResult.FoundNote","note":{"id":1,"title":"test","content":"Hi"}}

$ curl 'http://localhost:1234/notes/1?title=Test&content=Hello' -XPOST -w '\n\n'
{"$type":"example.ApiResult.UpdatedNote","updatedNote":{"id":1,"title":"Test","content":"Hello"}}

$ curl 'http://localhost:1234/notes?title=Post2&content=Content' -XPOST -w '\n\n'
{"$type":"example.ApiResult.CreatedNote","note":{"id":2,"title":"Post2","content":"Content"}}

$ curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[{"id":1,"title":"Test","content":"Hello"},{"id":2,"title
":"Post2","content":"Content"}]}

$ curl 'http://localhost:1234/notes/1' -XDELETE -w '\n\n'
{"$type":"example.ApiResult.DeletedNote","deletedNote":{"id":1,"title":"Test","content":"Hello"}}

$ curl 'http://localhost:1234/notes' -w '\n\n'
{"$type":"example.ApiResult.ListNotes","notes":[{"id":2,"title":"Post2","content":"Content"}]}
```

However, if you took a close look at the code, you'll notice multiple problems:

* I didn't implement status codes because it wouldn't be very easy to. For
  example, if I wanted to add status codes on the `GET` route, I'd have to pattern
  match on the `ApiResult` inside of the `Future` and select the correct status
  code.
* The content type isn't `application/json`. It's just plain text (test it in your
  browser!
* It's tedious and boring to extend. For example, if I wanted to add status codes
  for all routes, I'd have to pattern match in each route!
* It will be easy to introduce bugs: during a refactor, someone could
  accidentally cause a `Created` status code to be returned along with an
  `ApiResult.DeletedNote`!
* We have to call `write` on each route. This code duplication is a clear sign
  that we can increase abstraction.
* It involves far too much boilerplate for such a simple API.

## Building marshallers

A core part of Akka HTTP is its [marshalling API][marshaller_docs]. Marshallers
describe how data is transformed, such as when an incoming HTTP request is
deserialized into, say, an int, or when an entity model is serialized into an
HTTP response. They are automatically resolved through implicit scoping and help
convert data returned in `complete` directives into HTTP responses. This is
perfect for the above situation: a marshaller can be used not only ensure the
proper status code and content type is returned, but it can also be used to
handle serialization, and thus, remove boilerplate.

Let's take a look at the type signature of `Marshaller`:

```scala
// From the ScalaDoc
sealed abstract class Marshaller[-A, +B] extends AnyRef
```

A `Marshaller[A, B]` serializes `A` into `B`. The docs recommend composing new
marshallers using existing ones, an approach we'll take to create a marshaller
for `ApiResult`. `Marshaller` provides methods such as `map` and `compose`,
which can transform the output or the input, respectively. We're going to focus
on the `compose` method:

```scala
// From the ScalaDoc
// Given a Marshaller[A, B]:
def compose[C](f: (C) ⇒ A): Marshaller[C, B] 
```

Based on the types, if `compose` is called on a `Marshaller[A, B]` with a
function that converts `C` to `A`, then the result is a `Marshaller[C, B]`. In
other words, `compose` creates a new marshaller that accepts `C`, transforms it
to `A` with `f`, and then passes it to the original marshaller `m`, which then
converts `A` to `B`.

We should be able to serialize an `ApiResult` with `compose`. If you read the
documentation, you'll see that a `ToEntityMarshaller[A]`, which is a
`Marshaller[A,MessageEntity]`, is used to generate the body of an HTTP request.
That means that we want a call `compose` on some marshaller to get a
`ToEntityMarshaller[ApiResult]`. In the earlier code, `ApiResult` was serialized
into a `String` using `write[ApiResult]`. This gives us a clue: if we convert
`ApiResult` into a `String` first, we'll have our marshaller. Conveniently, Akka
HTTP provides `Marshaller.StringMarshaller`, so we'll just call the `compose`
method on it:

```scala
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshalling._
/**
  * Mix in to provide marshalling capabilities for `ApiResult`.
  */
trait ApiMarshalling {
  /* 
   * Marshaller that serializes an ApiResult into JSON and ensures that it has
   * a content type of application/json
   */
  def apiResultTEM: ToEntityMarshaller[ApiResult] =
    Marshaller.StringMarshaller.compose(r => write[ApiResult](r))
}
```

Though this marshaller works, we could improve it by also specifying the content
typ, since the marshaller is used to create the message body. The `wrap` method,
which is basically the same thing as `compose` with an additional content type
parameter, allows us to do that:

```scala
// From the ScalaDoc
// Given a Marshaller[A, B]:
def wrap[C, D >: B](newMediaType: MediaType)(f: (C) ⇒ A)(implicit mto: ContentTypeOverrider[D]): Marshaller[C, D]
```

Using `wrap` instead of `compose` is a simple change away:

```scala
def apiResultTEM: ToEntityMarshaller[ApiResult] =
  Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(r => write[ApiResult](r))
}
```

We could make `apiResponseTEM` implicit, but we're not done yet: we still need
to ensure the proper status code is returned. To do so, we need to write a
`ToResponseMarshaller[ApiResult]`, which is a
`Marshaller[ApiResult,HttpResponse]`. The approach is the same: find an existing
marshaller and use one of its composition methods. First, we need to figure out
which status code should be returned given an `ApiResult`, so let's write a
helper method.

```scala
import ApiResult._
def getResponseCode(result: ApiResult): StatusCode = result match {
  case _: ListNotes => StatusCodes.OK
  case _: FoundNote => StatusCodes.OK
  case _: NoteNotFound => StatusCodes.NotFound
  case _: CreatedNote => StatusCodes.Created
  // To return a 304 Not Modified, UpdatedNote and the associated logic needs to
  // be changed so that it can capture the possibility that no changes were made.
  // Try it as an exercise!
  case _: UpdatedNote => StatusCodes.OK
  case _: DeletedNote => StatusCodes.OK
}
```

Then, we use another predefined marshaller to construct the
`ToResponseMarshaller[ApiResult]` that will convert each `ApiResult` into a
`Response`.

```scala
/*
 * Marshaller that converts an ApiResult into an HttpResponse
 */
implicit def apiResultTRM: ToResponseMarshaller[ApiResult] =
    Marshaller
      .fromStatusCodeAndHeadersAndValue(apiResultTEM)
      .compose(apiResult => (getResponseCode(apiResult), List.empty, apiResult))
```

The `Marshaller.fromStatusCodeAndHeadersAndValue` used above returns a
marshaller that can construct an `HttpResponse`. The method has the following
signature:

```scala
// From the ScalaDoc
implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(StatusCode, Seq[HttpHeader], T)] 
```

`compose` is used to ensure that the status code is included in the
response.

## Putting it all together

Finally, let's mix in the `ApiMarshalling` trait.

```scala
trait Routes extends ApiMarshalling {
  def noteService: NoteService
  
  val notesRoute =
    pathPrefix("notes") {
      path(IntNumber) { id =>
        get {
          complete {
            noteService.find(id)
          }
        }
        // ...
      }
    }
    // Rest omitted because it's mostly the same
}
```

Notice the differnce between the original code and the code using the
marshaller:

* `ApiResult` is automatically serialized, so we don't have to call `write`
  anymore. Plus, Akka HTTP has implicit functions to derive
  `ToResponseMarshaller[Future[A]]` from a `ToResponseMarshaller[A]`, so we can
  just leave each call as a `Future[ApiResult]`.
* The status code is correctly included in the response.
* The content type is `application/json`.
* When a new `ApiResult` is added, the only status code or serialization logic
  that needs to be implemented is a case in `getResponseCode`. In addition, the
  compiler will even issue a warning as a reminder!

## Conclusion

While the example shown in this article is relatively simple, it's easy to see
how marshallers can be used to reduce boilerplate and unify logic. They're also
extensible: if you wanted to implement XML serialization, all you'd have to do
is create a `ToEntityMarshaller[ApiResult]` for XML, refactor `apiResultTEM` to
accept `apiResultTEM` as a parameter, and pass the XML marshaller to
`apiResultTEM`. One marshaller that I found very useful to have, in particular,
is a marshaller for a `Task` monad. It makes libraries such as `fs2` or `doobie`
interop very well with Akka HTTP. Anyways, if you'd like to learn more about
marshallers, check out the [documentation][marshaller_docs]. There are many more
ways to use marshallers than I have presented in this article, such as streaming
or chunking responses.

[akka_http]: http://doc.akka.io/docs/akka-http/current/scala/http/introduction.html
[marshaller_docs]: http://doc.akka.io/docs/akka-http/current/scala/http/common/marshalling.html
[impl_gist]: https://gist.github.com/Technius/4fddfc9e33b6c2fff13cbea5e4c67d53
